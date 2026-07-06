package org.example.ai.agent.router;

/**
 * Agent 问题路由类型。
 *
 * 用来标识用户问题应该进入哪一条处理链路。
 */
public enum RouteType {

    /**
     * 只查企业知识库文档。
     *
     */
    RAG_ONLY,

    /**
     * 只查业务系统数据。
     *
     * 示例：
     * - 查询 A 项目的合同金额
     * - 最近 5 个合同有哪些？
     */
    BUSINESS_QUERY,

    /**
     * 业务数据 + 知识库文档混合问答。
     *
     * 示例：
     * - 查询 A 项目回款情况，并分析有没有风险
     */
    MIXED_QUERY,

    /**
     * 统计分析类问题。
     *
     * 示例：
     * - 本月合同金额按项目类型统计
     */
    STATISTIC_QUERY,

    /**
     * 工作流动作类问题。
     *
     * 示例：
     * - 帮我发起合同审批
     */
    WORKFLOW_ACTION,

    /**
     * 信息不足，需要追问用户。
     */
    CLARIFY,

    /**
     * 危险或超出范围的问题，直接拒绝。
     */
    REJECT
}