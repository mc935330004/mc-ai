package org.example.ai.agent.common.enums.protocol;

/**
 * 单个回答区块的状态。
 */
public enum BlockStatus {

    /**
     * 等待生成。
     */
    PENDING,

    /**
     * 正在流式生成。
     *
     * 主要用于TEXT区块。
     */
    STREAMING,

    /**
     * 区块数据已经完整，可以正常展示。
     */
    READY,

    /**
     * 当前区块生成失败。
     */
    FAILED,

    /**
     * 当前区块被用户终止。
     */
    CANCELLED
}