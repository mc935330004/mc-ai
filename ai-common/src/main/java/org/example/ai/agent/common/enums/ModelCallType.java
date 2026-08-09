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
     * 最终业务回答。
     */
    ANSWER,
    /**
     * 工作流回答模型重试。
     *
     * 与ANSWER分开记录，避免正常调用和重试调用
     * 使用相同callSequence时无法区分。
     */
    ANSWER_RETRY,
    /**
     *  判断当前问题是否依赖上一轮会话，并生成独立完整问题。
     */
    CONTEXT_REWRITE,

    /**
     * 直接聊天。
     */
    DIRECT_CHAT,

    /**
     * 字段语义生成。
     */
    FIELD_SEMANTIC,

    /**
     * 管理员对指定模型执行基础连接测试。
     *
     * 该调用不能使用备用模型，避免掩盖被测模型故障。
     */
    MODEL_CONNECTIVITY_TEST,

    /**
     * 业务能力参数提取。
     *
     * 能力选择完成后，单独调用模型，
     * 只根据该能力的 inputSchemaJson 提取接口参数。
     */
    PARAMETER_EXTRACTOR,

    /**
     * 动态能力规划。
     */
    PLANNER,

    /**
     * 知识库 RAG 回答。
     */
    RAG,
    /**
     * 回答格式或内容修复。
     */
    REPAIR,
    /**
     * 上一轮结果本地统计规划。
     *
     * 大模型只选择统计方式和字段，
     * 不读取完整业务数据，也不执行金额计算。
     */
    RESULT_ANALYSIS_PLANNER,
    /**
     * 已选工作流参数提取。
     */
    WORKFLOW_PARAMETER_EXTRACTOR,
    /**
     * 已发布工作流选择。
     */
    WORKFLOW_PLANNER;

    /**
     * 当前调用是否允许使用用户在聊天页面选择的模型。
     *
     * 新增调用类型时默认返回false，
     * 避免内部调用误用缺少结构化能力的模型。
     */
    public boolean usesUserSelectedModel() {
        return switch (this) {
            case ANSWER,
                 ANSWER_RETRY,
                 RAG,
                 DIRECT_CHAT -> true;
            default -> false;
        };
    }

    /**
     * 当前调用是否要求模型支持结构化输出。
     */
    public boolean requiresStructuredOutput() {
        return switch (this) {
            case PLANNER,
                 PARAMETER_EXTRACTOR,
                 WORKFLOW_PLANNER,
                 WORKFLOW_PARAMETER_EXTRACTOR,
                 CONTEXT_REWRITE,
                 RESULT_ANALYSIS_PLANNER,
                 FIELD_SEMANTIC,
                 REPAIR -> true;
            default -> false;
        };
    }

}