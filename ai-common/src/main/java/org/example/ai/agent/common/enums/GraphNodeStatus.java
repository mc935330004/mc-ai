package org.example.ai.agent.common.enums;

/**
 * GraphSpec节点执行状态。
 */
public enum GraphNodeStatus {

    SUCCESS,

    FAILED,

    /**
     * 因上游失败而未执行。
     */
    SKIPPED
}