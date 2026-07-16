package org.example.ai.agent.common.enums;

/**
 * 工作流运行来源。
 */
public enum WorkflowRunOrigin {

    /**
     * AI聊天正常执行。
     */
    CHAT,

    /**
     * 管理端执行草稿调试。
     */
    DEBUG,

    /**
     * 重试历史运行中的失败项目。
     */
    RETRY
}