package org.example.ai.agent.common.enums;

public enum WorkflowPlanStatus {

    /**
     * 没有合适工作流，回退到单能力规划器。
     */
    NOT_MATCHED,

    /**
     * 已确定工作流，但参数不完整。
     */
    NEED_CLARIFY,

    /**
     * 已确定工作流和安全输入，可以执行。
     */
    READY
}