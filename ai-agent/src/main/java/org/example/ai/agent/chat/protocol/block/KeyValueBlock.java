package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;

import java.util.List;

/**
 * 项目、合同等基础信息区块。
 */
public record KeyValueBlock(String id, String title, int order, BlockStatus status, BlockSource source, List<DisplayValue> items)
        implements ResponseBlock {

    public KeyValueBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public BlockType type() {
        return BlockType.KEY_VALUE;
    }
}