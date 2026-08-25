package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.ValueType;

import java.util.List;

/**
 * 普通列表区块。
 *
 * CHAT模式默认最多展示10行，
 * 完整数量通过total返回。
 */
public record TableBlock(String id, String title, int order, BlockStatus status, BlockSource source, List<Column> columns,
                         List<Row> rows, long total, boolean hasMore) implements ResponseBlock {

    public TableBlock {
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
        return BlockType.TABLE;
    }

    /**
     * 表格列定义。
     */
    public record Column(
            String key,
            String label,
            ValueType valueType,
            String unit) {

        public Column {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "表格列key不能为空"
                );
            }

            key = key.trim();
            label = label == null ? "" : label.trim();
            valueType = valueType == null
                    ? ValueType.TEXT
                    : valueType;
            unit = unit == null ? "" : unit.trim();
        }
    }

    /**
     * 表格中的一行数据。
     */
    public record Row(
            String rowId,
            List<DisplayValue> cells) {

        public Row {
            if (rowId == null || rowId.isBlank()) {
                throw new IllegalArgumentException(
                        "表格行rowId不能为空"
                );
            }

            rowId = rowId.trim();
            cells = cells == null
                    ? List.of()
                    : List.copyOf(cells);
        }
    }
}