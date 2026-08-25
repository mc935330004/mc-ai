package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.common.enums.protocol.BlockType;

import java.util.Objects;

/**
 * 单个区块生成失败时发送的数据。
 *
 * 单个AI分析区块失败，
 * 不应清除已经成功返回的业务数据区块。
 */
public record BlockErrorPayload(
        String blockId,
        BlockType blockType,
        String errorCode,
        String errorMessage,
        boolean retryable) {

    public BlockErrorPayload {
        blockId = StreamSupport.requireText(
                blockId,
                "失败区块blockId不能为空"
        );

        blockType = Objects.requireNonNull(
                blockType,
                "失败区块blockType不能为空"
        );

        errorCode = StreamSupport.requireText(
                errorCode,
                "区块错误编码不能为空"
        );

        errorMessage = StreamSupport.normalizeText(errorMessage);
    }
}