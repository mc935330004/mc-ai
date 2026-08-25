package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;

import java.util.List;

/**
 * 树形层级列表区块。
 *
 * 适用于概算科目、组织层级等树形数据。
 */
public record TreeTableBlock(String id, String title, int order, BlockStatus status, BlockSource source, List<TableBlock.Column> columns,
                             List<Row> rows, long total) implements ResponseBlock {

    public TreeTableBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        total = Math.max(total, 0);
    }

    @Override
    public BlockType type() {
        return BlockType.TREE_TABLE;
    }

    /**
     * 树形表格中的一行数据。
     */
    public record Row(
            String rowId,
            List<DisplayValue> cells,
            List<Row> children) {

        public Row {
            if (rowId == null || rowId.isBlank()) {
                throw new IllegalArgumentException(
                        "树形表格行rowId不能为空"
                );
            }

            rowId = rowId.trim();

            cells = cells == null
                    ? List.of()
                    : List.copyOf(cells);

            children = children == null
                    ? List.of()
                    : List.copyOf(children);
        }
    }
}