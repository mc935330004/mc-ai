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

    private Set<Long> collectFieldIds(
            ReportDefinitionSpec definition) {

        Set<Long> fieldIds = new LinkedHashSet<>();

        for (ReportSectionSpec section :
                definition.sections()) {

            for (ReportFieldBindingSpec field : section.fields()) {

                if (field != null && field.fieldId() != null) {
                    fieldIds.add(field.fieldId());
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
     * 验证展示路径末级字段和字段字典机器字段一致。
     *
     * 工作流如果确实重命名了业务字段，
     * 应先为新机器字段发布对应字典，不能借用其他字段语义。
     */
    private void validateBindings(
            ReportDefinitionSpec definition,
            Map<Long, FieldDictionary> fieldsById) {

        for (ReportSectionSpec section :
                definition.sections()) {

            for (ReportFieldBindingSpec binding :
                    section.fields()) {

                FieldDictionary dictionary =
                        fieldsById.get(binding.fieldId());

                if (dictionary == null) {
                    continue;
                }

                String dictionaryFieldName =
                        resolveDictionaryFieldName(dictionary);

                String sourceFieldName =
                        resolveSourceFieldName(
                                binding.sourcePath()
                        );

                if (!StringUtils.hasText(dictionaryFieldName)|| !Objects.equals(dictionaryFieldName,sourceFieldName)) {
                    throw new IllegalStateException(
                            "报告字段路径与字段字典不一致，fieldId="
                                    + binding.fieldId()
                    );
                }
            }
        }
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