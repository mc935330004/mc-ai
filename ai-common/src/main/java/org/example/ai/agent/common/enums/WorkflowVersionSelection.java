package org.example.ai.agent.common.enums;

/**
 * 工作流版本选择方式。
 */
public enum WorkflowVersionSelection {

    /**
     * 正常聊天执行。
     *
     * 必须是当前活动版本，
     * 且和Planner选择的版本一致。
     */
    ACTIVE_PINNED,

    /**
     * 失败项目重试。
     *
     * 固定使用原运行的历史版本，
     * 即使该版本已经RETIRED。
     */
    EXACT_VERSION
}