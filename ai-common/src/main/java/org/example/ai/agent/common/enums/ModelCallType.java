package org.example.ai.agent.common.enums;

/**
 * 大模型调用类型。
 *
 * 作用：
 * 1. 区分一次聊天中不同阶段的模型调用。
 * 2. 方便统计规划、回答、RAG 分别消耗多少 Token。
 * 3. 避免在业务代码中散落字符串常量。
 */
public enum ModelCallType {

    /**
     * 动态能力规划。
     */
    PLANNER,
    /**
     * 业务能力参数提取。
     *
     * 能力选择完成后，单独调用模型，
     * 只根据该能力的 inputSchemaJson 提取接口参数。
     */
    PARAMETER_EXTRACTOR,
    /**
     * 最终业务回答。
     */
    ANSWER,

    /**
     * 知识库 RAG 回答。
     */
    RAG,

    /**
     * 字段语义生成。
     */
    FIELD_SEMANTIC,

    /**
     * 回答格式或内容修复。
     */
    REPAIR,

    /**
     * 直接聊天。
     */
    DIRECT_CHAT,

    /**
     * 已发布工作流选择。
     */
    WORKFLOW_PLANNER,

    /**
     * 已选工作流参数提取。
     */
    WORKFLOW_PARAMETER_EXTRACTOR,
    /**
     *  判断当前问题是否依赖上一轮会话，并生成独立完整问题。
     */
    CONTEXT_REWRITE,
    /**
     * 上一轮结果本地统计规划。
     *
     * 大模型只选择统计方式和字段，
     * 不读取完整业务数据，也不执行金额计算。
     */
    RESULT_ANALYSIS_PLANNER,
    /**
     * 工作流回答模型重试。
     *
     * 与ANSWER分开记录，避免正常调用和重试调用
     * 使用相同callSequence时无法区分。
     */
    ANSWER_RETRY,
}