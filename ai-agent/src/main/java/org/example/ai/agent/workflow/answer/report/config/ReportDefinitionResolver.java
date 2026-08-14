package org.example.ai.agent.workflow.answer.report.config;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.graph.GraphSpecParser;
import org.example.ai.agent.graph.model.GraphSpec;
import org.example.ai.agent.graph.model.ReportFieldBindingSpec;
import org.example.ai.agent.graph.model.report.ReportDefinitionSpec;
import org.example.ai.agent.graph.model.report.ReportSectionSpec;
import org.example.ai.agent.workflow.answer.WorkflowCapabilityCodeCollector;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.example.ai.agent.common.enums.report.ReportAggregationType;
import org.example.ai.agent.graph.model.report.ReportCalculationSpec;
import org.example.ai.agent.graph.model.report.ReportCalculationTermSpec;
import java.util.*;

/**
 * 从工作流实际执行版本中读取报告定义。
 *
 * 草稿配置不能参与正式报告生成。
 */
@Component
@RequiredArgsConstructor
public class ReportDefinitionResolver {

    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final WorkflowCapabilityCodeCollector capabilityCodeCollector;
    private final FieldDictionaryMapper fieldDictionaryMapper;
    private final GraphSpecParser graphSpecParser;
    /**
     * 允许参与数值聚合的字段字典类型。
     */
    private static final Set<String> NUMBER_FIELD_TYPES = Set.of(
            "number",
            "integer",
            "int",
            "long",
            "float",
            "double",
            "decimal",
            "bigdecimal",
            "numeric"
    );
    public Optional<ResolvedReportDefinition> resolve(
            WorkflowExecutionOutcome outcome) {

        if (outcome == null
                || outcome.versionId() == null
                || !StringUtils.hasText(
                outcome.workflowCode())) {

            return Optional.empty();
        }

        PublishedWorkflow workflow =
                snapshotResolver.resolveExactVersion(
                        outcome.workflowCode(),
                        outcome.versionId()
                );

        GraphSpec graph = graphSpecParser.parse(
                workflow.version().getSnapshotJson()
        );

        ReportDefinitionSpec definition =
                graph.getReportDefinition();

        if (definition == null) {
            return Optional.empty();
        }

        Set<Long> fieldIds =
                collectFieldIds(definition);

        if (fieldIds.isEmpty()) {
            throw new IllegalStateException(
                    "报告定义没有配置字段字典"
            );
        }

        List<FieldDictionary> dictionaries =
                fieldDictionaryMapper.selectBatchIds(
                        fieldIds
                );

        Map<Long, FieldDictionary> fieldsById =
                indexFields(dictionaries);

        Set<String> workflowCapabilities =
                new LinkedHashSet<>(
                        capabilityCodeCollector.collect(
                                workflow.compiledGraph()
                        )
                );

        validateFields(
                fieldIds,
                fieldsById,
                workflowCapabilities
        );
        // 字段路径必须与字段字典机器字段一致，避免业务含义错配。
        validateBindings(
                definition,
                fieldsById
        );
        return Optional.of(
                new ResolvedReportDefinition(
                        definition,
                        fieldsById
                )
        );
    }

    /**
     * 收集报告引用的全部字段字典 ID。
     */
    /**
     * 收集普通展示字段、文件字段和计算字段引用的全部字典ID。
     */
    private Set<Long> collectFieldIds(ReportDefinitionSpec definition) {
        Set<Long> fieldIds = new LinkedHashSet<>();
        for (ReportSectionSpec section : definition.sections()) {

            for (ReportFieldBindingSpec field : section.fields()) {
                if (field == null) {
                    continue;
                }
                if (field.fieldId() != null) {
                    fieldIds.add(field.fieldId());
                }
                if (field.fileUrlFieldId() != null) {
                    fieldIds.add(
                            field.fileUrlFieldId()
                    );
                }
            }

            for (ReportCalculationSpec calculation : section.calculations()) {
                if (calculation == null) {
                    continue;
                }

                for (ReportCalculationTermSpec term : calculation.terms()) {
                    if (term != null && term.fieldId() != null) {
                        fieldIds.add(term.fieldId());
                    }
                }
            }
        }

        return fieldIds;
    }

    private Map<Long, FieldDictionary> indexFields(List<FieldDictionary> dictionaries) {

        Map<Long, FieldDictionary> result = new LinkedHashMap<>();

        if (dictionaries == null) {
            return result;
        }

        for (FieldDictionary dictionary : dictionaries) {

            if (dictionary != null && dictionary.getId() != null) {

                result.put(dictionary.getId(), dictionary);
            }
        }

        return result;
    }

    private void validateFields(Set<Long> fieldIds, Map<Long, FieldDictionary> fieldsById, Set<String> workflowCapabilities) {

        for (Long fieldId : fieldIds) {
            FieldDictionary field =
                    fieldsById.get(fieldId);

            if (field == null) {
                throw new IllegalStateException(
                        "报告引用的字段字典不存在："
                                + fieldId
                );
            }

            if (!"PUBLISHED".equalsIgnoreCase(
                    field.getPublishStatus())) {

                throw new IllegalStateException(
                        "报告字段字典尚未发布："
                                + fieldId
                );
            }

            if (Integer.valueOf(0).equals(
                    field.getVisible())) {

                throw new IllegalStateException(
                        "报告字段不允许展示："
                                + fieldId
                );
            }

            if (!workflowCapabilities.contains(
                    field.getCapabilityCode())) {

                throw new IllegalStateException(
                        "报告字段不属于当前工作流能力："
                                + fieldId
                );
            }
        }
    }

    /**
     * 验证普通字段、文件字段和计算字段的字典绑定。
     */
    private void validateBindings(
            ReportDefinitionSpec definition,
            Map<Long, FieldDictionary> fieldsById) {

        for (ReportSectionSpec section :
                definition.sections()) {

            for (ReportFieldBindingSpec binding : section.fields()) {

                FieldDictionary dictionary = fieldsById.get(binding.fieldId());

                if (dictionary == null) {
                    continue;
                }
                if (binding.fileList()) {
                    validateFileBinding(
                            binding,
                            dictionary,
                            fieldsById
                    );
                    continue;
                }
                validateBindingFieldName(dictionary, binding.sourcePath(), binding.fieldId());
            }
            validateCalculationBindings(section, fieldsById);
        }
    }
    /**
     * 验证计算指标引用的字段字典和取值路径。
     */
    private void validateCalculationBindings(ReportSectionSpec section, Map<Long, FieldDictionary> fieldsById) {

        for (ReportCalculationSpec calculation : section.calculations()) {

            if (calculation == null) {
                throw new IllegalStateException(
                        "报告计算指标配置不能为空"
                );
            }

            for (ReportCalculationTermSpec term : calculation.terms()) {

                validateCalculationTermBinding(
                        term,
                        fieldsById
                );
            }
        }
    }

    /**
     * 验证单个计算项的字段字典绑定。
     */
    private void validateCalculationTermBinding(
            ReportCalculationTermSpec term,
            Map<Long, FieldDictionary> fieldsById) {

        if (term == null || term.fieldId() == null) {
            throw new IllegalStateException(
                    "报告计算项没有配置字段字典"
            );
        }

        FieldDictionary dictionary = fieldsById.get(term.fieldId());

        if (dictionary == null) {
            /*
             * 正常情况下前面的字段完整性校验会先阻止，
             * 这里保留运行时保护。
             */
            throw new IllegalStateException(
                    "报告计算字段字典不存在："
                            + term.fieldId()
            );
        }

        validateBindingFieldName(
                dictionary,
                term.sourcePath(),
                term.fieldId()
        );

        /*
         * COUNT只统计有效标量数量，可以作用于字符串字段。
         * 其他聚合方式必须使用数字字段。
         */
        if (term.aggregation() != ReportAggregationType.COUNT && !isNumberField(dictionary)) {

            throw new IllegalStateException(
                    "报告计算字段不是数字类型，fieldId="
                            + term.fieldId()
            );
        }
    }

    /**
     * 判断字段字典是否声明为数字类型。
     */
    private boolean isNumberField(FieldDictionary dictionary) {

        if (dictionary == null
                || !StringUtils.hasText(
                dictionary.getFieldType())) {

            return false;
        }

        String fieldType = dictionary
                .getFieldType()
                .trim()
                .toLowerCase(Locale.ROOT);

        return NUMBER_FIELD_TYPES.contains(fieldType);
    }
    /**
     * 验证文件名和文件地址都来自同一个文件数组。
     */
    private void validateFileBinding(
            ReportFieldBindingSpec binding,
            FieldDictionary nameDictionary,
            Map<Long, FieldDictionary> fieldsById) {

        FieldDictionary urlDictionary =fieldsById.get(binding.fileUrlFieldId());

        if (urlDictionary == null) {
            throw new IllegalStateException(
                    "文件地址字段字典不存在："
                            + binding.fileUrlFieldId()
            );
        }

        if (!"file_list".equalsIgnoreCase(
                nameDictionary.getDisplayFormat())) {

            throw new IllegalStateException(
                    "文件名字典必须配置 displayFormat=file_list，fieldId="
                            + binding.fieldId()
            );
        }

        validateBindingFieldName(
                nameDictionary,
                binding.fileNamePath(),
                binding.fieldId()
        );

        validateBindingFieldName(
                urlDictionary,
                binding.fileUrlPath(),
                binding.fileUrlFieldId()
        );

        String nameParentPath =
                resolveParentPath(nameDictionary.getFieldPath());

        String urlParentPath =
                resolveParentPath(urlDictionary.getFieldPath());

        if (!Objects.equals(nameParentPath, urlParentPath)) {
            throw new IllegalStateException(
                    "文件名和文件地址字段不属于同一个文件数组"
            );
        }
        // 校验字段字典父级确实是文件对象数组。
        if (!StringUtils.hasText(nameParentPath)
                || !nameParentPath.endsWith("[]")) {

            throw new IllegalStateException(
                    "文件字段字典父级必须是对象数组，fieldId="
                            + binding.fieldId()
            );
        }

        // 校验报告读取的数组与字段字典声明的数组一致。
        String dictionaryArrayName = resolveSourceFieldName(nameParentPath);
        String sourceArrayName =resolveSourceFieldName(binding.sourcePath());

        if (!Objects.equals(dictionaryArrayName,sourceArrayName)) {
            throw new IllegalStateException(
                    "文件数组路径与字段字典不一致，fieldId="
                            + binding.fieldId()
            );
        }
    }

    /**
     * 校验路径末级字段与字典机器字段一致。
     */
    private void validateBindingFieldName(
            FieldDictionary dictionary,
            String sourcePath,
            Long fieldId) {

        String dictionaryFieldName =
                resolveDictionaryFieldName(dictionary);

        String sourceFieldName =
                resolveSourceFieldName(sourcePath);

        if (!StringUtils.hasText(dictionaryFieldName)
                || !Objects.equals(
                dictionaryFieldName,
                sourceFieldName
        )) {
            throw new IllegalStateException(
                    "报告字段路径与字段字典不一致，fieldId="
                            + fieldId
            );
        }
    }

    /**
     * 获取字段字典路径的共同父级。
     */
    private String resolveParentPath(String fieldPath) {
        if (!StringUtils.hasText(fieldPath)) {
            return null;
        }

        String value = fieldPath.trim();
        int separatorIndex = value.lastIndexOf('.');

        return separatorIndex > 0
                ? value.substring(0, separatorIndex)
                : null;
    }

    /**
     * 优先读取字段字典中的稳定机器字段名称。
     */
    private String resolveDictionaryFieldName(
            FieldDictionary dictionary) {

        if (StringUtils.hasText(
                dictionary.getFieldName())) {

            return dictionary
                    .getFieldName()
                    .trim();
        }

        return resolveSourceFieldName(
                dictionary.getFieldPath()
        );
    }

    /**
     * 提取受限路径中的末级字段名称。
     */
    private String resolveSourceFieldName(String sourcePath) {
        if (!StringUtils.hasText(sourcePath)) {
            return null;
        }

        String normalized =sourcePath.trim()
                        .replace("[]", "");

        int separatorIndex =normalized.lastIndexOf('.');

        String fieldName =separatorIndex >= 0
                        ? normalized.substring(
                        separatorIndex + 1
                )
                        : normalized;

        return StringUtils.hasText(fieldName)
                ? fieldName
                : null;
    }
}
