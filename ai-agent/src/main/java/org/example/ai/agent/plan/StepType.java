package org.example.ai.agent.plan;

/**
 * 计划步骤类型。
 *
 * RoutePlan 会由多个 PlanStep 组成，
 * 每个 PlanStep 都需要声明自己属于哪种执行类型。
 */
public enum StepType {

    /**
     * 调用业务系统能力。
     *
     * 示例：
     * - 查询项目
     * - 查询合同
     * - 查询回款
     */
    BUSINESS_TOOL,

    /**
     * 调用企业知识库 RAG 检索。
     *
     * 示例：
     * - 检索合同审批制度
     * - 检索项目风险管理办法
     */
    RAG,

    /**
     * 调用大模型做最终总结。
     *
     * 注意：
     * 大模型只负责基于已有数据总结，
     * 不负责凭空编造业务数据。
     */
    LLM_SUMMARY,

    /**
     * 返回澄清问题。
     *
     * 当用户问题不明确时使用。
     */
    CLARIFY,

    /**
     * 拒绝执行。
     *
     * 当命中危险操作或超出系统范围时使用。
     */
    REJECT
}