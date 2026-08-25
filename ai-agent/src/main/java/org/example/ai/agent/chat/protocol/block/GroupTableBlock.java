package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;

import java.util.List;

/**
 * 分组列表区块。
 *
 * 适用于按照单位、合同、项目等维度分组的数据。
 */
public record GroupTableBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        List<TableBlock.Column> columns,
        List<Group> groups,
        long total,
        boolean hasMore)
        implements ResponseBlock {

    public GroupTableBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);

        columns = columns == null
                ? List.of()
                : List.copyOf(columns);

        groups = groups == null
                ? List.of()
                : List.copyOf(groups);

        total = Math.max(total, 0);
    }

    @Override
    public BlockType type() {
        return BlockType.GROUP_TABLE;
    }

    /**
     * 一个分组的数据。
     */
    public record Group(
            String groupId,
            String title,
            List<DisplayValue> summaryItems,
            List<TableBlock.Row> rows) {

        public Group {
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException(
                        "分组groupId不能为空"
                );
            }

            groupId = groupId.trim();
            title = title == null ? "" : title.trim();

            summaryItems = summaryItems == null
                    ? List.of()
                    : List.copyOf(summaryItems);

            rows = rows == null
                    ? List.of()
                    : List.copyOf(rows);
        }
    }
}