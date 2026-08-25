package org.example.ai.agent.common.enums.protocol;

/**
 * AI回答统一SSE事件类型。
 *
 * TEXT区块允许增量输出，
 * 其他结构化区块必须在数据完整后一次发送。
 */
public enum ResponseStreamEventType {

    /**
     * 一次回答开始。
     */
    RESPONSE_START,

    /**
     * 单个区块开始生成。
     *
     * 主要用于流式TEXT区块。
     */
    BLOCK_START,

    /**
     * 单个区块的增量内容。
     *
     * 只允许TEXT区块使用。
     */
    BLOCK_DELTA,

    /**
     * 单个区块已经完整生成。
     */
    BLOCK_DONE,

    /**
     * 单个区块生成失败。
     *
     * 不应直接导致其他成功区块消失。
     */
    BLOCK_ERROR,

    /**
     * 写操作参数收集表单。
     */
    ACTION_FORM,

    /**
     * 写操作执行前确认预览。
     */
    ACTION_PREVIEW,

    /**
     * 完整回答快照。
     *
     * 用于内容校验、页面刷新和异常恢复。
     */
    RESPONSE_SNAPSHOT,

    /**
     * 一次回答正常结束。
     */
    RESPONSE_DONE,

    /**
     * 一次回答整体失败。
     */
    RESPONSE_ERROR,

    /**
     * SSE连接心跳。
     */
    HEARTBEAT
}