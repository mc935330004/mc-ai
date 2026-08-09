package org.example.ai.agent.workflow.answer.report.config;

import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.graph.model.report.ReportDefinitionSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 已通过工作流和字段字典校验的报告定义。
 */
public record ResolvedReportDefinition(
        ReportDefinitionSpec definition,
        Map<Long, FieldDictionary> fieldsById) {

    public ResolvedReportDefinition {
        fieldsById = fieldsById == null
                ? Map.of()
                : Map.copyOf(
                new LinkedHashMap<>(fieldsById)
        );
    }

    public FieldDictionary requireField(Long fieldId) {
        FieldDictionary field = fieldsById.get(fieldId);

        if (field == null) {
            throw new IllegalStateException(
                    "报告字段字典不存在：" + fieldId
            );
        }

        return field;
    }
}