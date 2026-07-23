package org.example.ai.agent.common.enums;

/**
 * FOREACH中单个循环项的执行状态。
 */
public enum ForEachItemStatus {

    /**
     * 当前循环项及其全部子流程执行成功。
     */
    SUCCESS,

    /**
     * 当前循环项返回了可用结果，
     * 但其子流程中存在失败或主动跳过的记录。
     */
    PARTIAL_SUCCESS,

    /**
     * 当前循环项执行失败。
     */
    FAILED,

    /**
     * 当前循环项没有执行。
     *
     * 可能原因：
     * 1. 缺少必要字段，被工作流规则主动跳过；
     * 2. continueOnItemError=false时，前一项失败导致后续项未执行。
     */
    SKIPPED
}