package org.example.ai.agent.workflow.answer;

/**
 * 工作流字段语义、权限和展示元数据。
 *
 * 工作流内部字段、模型可见字段和用户可见字段
 * 继续保持三个独立边界。
 */
public record WorkflowAnswerFieldContext(
        Long fieldId,
        String capabilityCode,
        String fieldName,
        String label,
        String meaning,
        String format,
        String group,
        String fieldPath,
        String fieldType,
        boolean modelVisible,
        boolean userVisible,
        String fieldCode,
        Integer displayOrder,
        boolean requiredOutput,
        String importance,
        String displayComponent,
        Integer summaryFlag,
        String unit,
        Integer precisionScale,
        String valueSource,
        String enumMappingJson,
        String nullDisplayText) {
}