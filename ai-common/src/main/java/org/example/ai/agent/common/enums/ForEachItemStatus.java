package org.example.ai.agent.common.enums;

/**
 * FOREACH中单个项目的执行状态。
 */
public enum ForEachItemStatus {

    SUCCESS,

    FAILED,

    /**
     * continueOnItemError=false时，
     * 前一项失败导致后续项未执行。
     */
    SKIPPED
}