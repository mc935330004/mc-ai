package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;

import java.util.List;

/**
 * 金额、比例和数量等核心指标区块。
 */
public record MetricsBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        List<DisplayValue> items)
        implements ResponseBlock {

    public MetricsBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);

        items = items == null
                ? List.of()
                : List.copyOf(items);
    }

    @Override
    public BlockType type() {
        return BlockType.METRICS;
    }
}