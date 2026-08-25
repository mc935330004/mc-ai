package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;

/**
 * 自然语言区块。
 *
 * 只有TEXT区块允许保存Markdown，
 * 并且允许通过SSE增量输出。
 */
public record TextBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        String markdown) implements ResponseBlock {

    public TextBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);
        markdown = markdown == null ? "" : markdown;
    }

    @Override
    public BlockType type() {
        return BlockType.TEXT;
    }
}