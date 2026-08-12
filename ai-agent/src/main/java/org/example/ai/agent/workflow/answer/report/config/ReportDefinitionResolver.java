package org.example.ai.agent.workflow.answer.report.config;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.graph.GraphSpecParser;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
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

        return resolveDefinition(
                definition,
                workflow.compiledGraph()
        );
    }

    /**
     * 解析临时工作流中的报告定义。
     *
     * 草稿入口只使用已经通过编译校验的临时图，
     * 不读取或伪造正式发布版本。
     */
    public Optional<ResolvedReportDefinition> resolveDraft(
            GraphSpec graph,
            CompiledGraphSpec compiledGraph) {

        if (graph == null || compiledGraph == null) {
            return Optional.empty();
        }

        return resolveDefinition(
                graph.getReportDefinition(),
                compiledGraph
        );
    }

    /**
     * 使用统一的字段字典规则解析报告定义。
     */
    private Optional<ResolvedReportDefinition> resolveDefinition(
            ReportDefinitionSpec definition,
            CompiledGraphSpec compiledGraph) {

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
                                compiledGraph
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
                    fieldIds.add(field.fileUrlFieldId());
                }
            }
        }

        return fieldIds;
    }

    private Map<Long, FieldDictionary> indexFields(
            List<FieldDictionary> dictionaries) {

        Map<Long, FieldDictionary> result =
                new LinkedHashMap<>();

        if (dictionaries == null) {
            return result;
        }

        for (FieldDictionary dictionary :
                dictionaries) {

            if (dictionary != null
                    && dictionary.getId() != null) {

                result.put(
                        dictionary.getId(),
                        dictionary
                );
            }
        }

        return result;
    }

    private void validateFields(
            Set<Long> fieldIds,
            Map<Long, FieldDictionary> fieldsById,
            Set<String> workflowCapabilities) {

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
     * 验证普通字段和文件字段的字典绑定。
     */
    private void validateBindings(
            ReportDefinitionSpec definition,
            Map<Long, FieldDictionary> fieldsById) {

        for (ReportSectionSpec section : definition.sections()) {
            for (ReportFieldBindingSpec binding : section.fields()) {

                FieldDictionary dictionary =
                        fieldsById.get(binding.fieldId());

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

                validateBindingFieldName(
                        dictionary,
                        binding.sourcePath(),
                        binding.fieldId()
                );
            }
        }
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
