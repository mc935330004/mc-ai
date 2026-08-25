package org.example.ai.agent.chat.protocol.stream;

/**
 * TEXT区块的增量文字内容。
 *
 * 该事件只能用于TEXT区块，
 * 不允许用增量字符串拼装表格等结构化区块。
 */
public record BlockDeltaPayload(
        String blockId,
        long deltaIndex,
        String delta) {

    public BlockDeltaPayload {
        blockId = StreamSupport.requireText(
                blockId,
                "增量区块blockId不能为空"
        );

        deltaIndex = Math.max(deltaIndex, 0);
        delta = delta == null ? "" : delta;
    }
}