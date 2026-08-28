package org.example.ai.agent.common.enums;

/**
 * Agent SSE事件类型。
 *
 * 运行过程事件和最终回答事件分开管理。
 */
public enum AgentStreamEventType {

    /**
     * Agent运行已经开始。
     */
    RUN_STARTED,

    /**
     * Agent正在处理。
     */
    THINKING,

    /**
     * 已生成执行计划。
     */
    PLAN,

    /**
     * 业务工具执行完成。
     */
    TOOL_RESULT,

    /**
     * WRITE参数收集表单。
     */
    ACTION_FORM,

    /**
     * 写操作确认预览。
     */
    ACTION_PREVIEW,

    /**
     * 服务端心跳。
     */
    HEARTBEAT,

    /**
     * 处理失败。
     */
    ERROR,

    /**
     * 已发布工作流执行结果。
     */
    WORKFLOW_RESULT,

    /**
     * 报告完成后的独立业务追问。
     */
    REPORT_FOLLOW_UP
}