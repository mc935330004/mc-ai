package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockType;

import java.util.Objects;

/**
 * 单个回答区块开始生成时发送的数据。
 *
 * TEXT区块可在该事件后继续发送BLOCK_DELTA，
 * 结构化区块可用于提前展示加载状态。
 */
public record BlockStartPayload(
        String blockId,
        BlockType blockType,
        String title,
        int order,
        BlockSource source) {

    public BlockStartPayload {
        blockId = StreamSupport.requireText(
                blockId,
                "区块blockId不能为空"
        );

        blockType = Objects.requireNonNull(
                blockType,
                "区块blockType不能为空"
        );

        title = StreamSupport.normalizeText(title);
        order = Math.max(order, 0);

        source = source == null
                ? BlockSource.SYSTEM
                : source;
    }
}