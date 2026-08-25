package org.example.ai.agent.common.enums.protocol;

/**
 * 一次AI回答的整体状态。
 */
public enum ResponseStatus {

    /**
     * 回答正在生成。
     */
    RUNNING,

    /**
     * 所有必要内容已经生成完成。
     */
    COMPLETED,

    /**
     * 业务数据可以展示，但部分非核心内容生成失败。
     *
     * 例如AI总结失败，但业务指标和表格正常。
     */
    PARTIAL,

    /**
     * 没有获得可展示的有效结果。
     */
    FAILED,

    /**
     * 用户主动终止生成。
     */
    CANCELLED
}