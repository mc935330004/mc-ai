package org.example.ai.agent.common.enums;

/**
 * 工作流默认展示方式。
 */
public enum WorkflowPresentationMode {

    /**
     * 用户明确要求报表时展示报表，其余默认文字回答。
     */
    AUTO,

    /**
     * 默认使用文字问答。
     */
    ANSWER,

    /**
     * 默认使用完整报表。
     */
    REPORT
}