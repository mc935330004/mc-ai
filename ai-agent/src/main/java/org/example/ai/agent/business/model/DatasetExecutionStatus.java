package org.example.ai.agent.business.model;

/**
 * 业务数据集的执行状态。
 */
public enum DatasetExecutionStatus {

    /** 等待执行。 */
    PENDING,

    /** 正在执行。 */
    RUNNING,

    /** 执行成功。 */
    SUCCESS,

    /** 执行成功但结果为空。 */
    EMPTY,

    /** 无权执行。 */
    DENIED,

    /** 执行失败。 */
    FAILED,

    /** 执行超时。 */
    TIMEOUT
}
