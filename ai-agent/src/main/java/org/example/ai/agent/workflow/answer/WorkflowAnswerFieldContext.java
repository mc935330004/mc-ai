package org.example.ai.agent.workflow.answer;

/**
 * 发送给工作流回答和统计模块的字段语义。
 *
 * 重要原则：
 * 1. fieldName、fieldPath用于程序定位字段；
 * 2. label、meaning用于大模型理解中文业务含义；
 * 3. aggregatable控制该字段是否允许参与金额、数量等统计；
 * 4. 这里只保存允许发送给模型的字段，不包含隐藏字段。
 */
public record WorkflowAnswerFieldContext(
        Long fieldId,
        String capabilityCode,
        String fieldName,
        String label,
        String meaning,
        String format,
        String group,

        /**
         * 字段在能力返回结果中的完整路径。
         *
         * 示例：
         * $.data.settlementInfos[].settlements[].thisAmount
         */
        String fieldPath,

        /**
         * 字段类型，例如：
         * string、number、integer。
         */
        String fieldType,

        /**
         * 是否允许参与聚合统计。
         */
        boolean aggregatable) {
}