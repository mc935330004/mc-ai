package org.example.ai.agent.workflow.answer;

/**
 * 发送给工作流回答模型的字段语义。
 *
 * @param capabilityCode 字段所属能力，仅供模型区分数据来源
 * @param fieldName      英文机器字段名称
 * @param label          中文展示名称
 * @param meaning        业务含义
 * @param format         展示格式，例如 amount、date、percent
 * @param group          字段所属展示分组
 */
public record WorkflowAnswerFieldContext(
        String capabilityCode,
        String fieldName,
        String label,
        String meaning,
        String format,
        String group) {
}