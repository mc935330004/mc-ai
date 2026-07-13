package org.example.ai.agent.common.enums;

/**
 * Agent SSE 事件类型。
 *
 * 使用枚举统一事件名称，避免业务代码中散落字符串。
 */
public enum AgentStreamEventType {

    /**
     * Agent 运行已经开始。
     */
    RUN_STARTED,

    /**
     * Agent 正在处理。
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
     * 已提取核心业务事实。
     */
    FACTS,

    /**
     * 最终回答即将开始。
     */
    ANSWER_START,

    /**
     * 最终回答增量内容。
     */
    ANSWER_DELTA,

    /**
     * 最终完整 Markdown 快照。
     */
    ANSWER_SNAPSHOT,

    /**
     * 最终回答发送完成。
     */
    ANSWER_DONE,

    /**
     * RAG 引用来源。
     */
    REFERENCES,

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
    ERROR
}