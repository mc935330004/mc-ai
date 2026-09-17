package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.ValueType;

import java.util.List;
import java.util.Set;

/**
 * 普通列表区块。
 *
 * CHAT 模式默认最多展示 10 行，
 * 完整数量通过 total 返回。
 */
public record TableBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        List<Column> columns,
        List<Row> rows,
        long total,
        boolean hasMore,
        boolean totalKnown,
        int pageNumber,
        int pageSize
) implements ResponseBlock {

    private static final Set<String> ACTION_TYPES =
            Set.of("SELECT_SUBJECT");

    public TableBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);
        columns = columns == null
                ? List.of()
                : List.copyOf(columns);
        rows = rows == null
                ? List.of()
                : List.copyOf(rows);

        total = Math.max(total, 0);
        pageNumber = Math.max(pageNumber, 1);
        pageSize = Math.max(pageSize, 1);

        // 兼容旧回答中没有 totalKnown 字段的情况。
        if (!totalKnown && total > 0) {
            totalKnown = true;
        }

        if (!totalKnown && total != 0) {
            throw new IllegalArgumentException(
                    "未知总数时 total 必须为 0"
            );
        }
    }

    /**
     * 兼容原有表格构造方式。
     */
    public TableBlock(
            String id,
            String title,
            int order,
            BlockStatus status,
            BlockSource source,
            List<Column> columns,
            List<Row> rows,
            long total,
            boolean hasMore
    ) {
        this(
                id,
                title,
                order,
                status,
                source,
                columns,
                rows,
                total,
                hasMore,
                true,
                1,
                defaultPageSize(rows)
        );
    }

    /**
     * 兼容只新增 totalKnown 的表格构造方式。
     */
    public TableBlock(
            String id,
            String title,
            int order,
            BlockStatus status,
            BlockSource source,
            List<Column> columns,
            List<Row> rows,
            long total,
            boolean hasMore,
            boolean totalKnown
    ) {
        this(
                id,
                title,
                order,
                status,
                source,
                columns,
                rows,
                total,
                hasMore,
                totalKnown,
                1,
                defaultPageSize(rows)
        );
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
            String unit
    ) {

        public Column {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "表格列 key 不能为空"
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
            List<DisplayValue> cells,
            Action action
    ) {

        public Row {
            if (rowId == null || rowId.isBlank()) {
                throw new IllegalArgumentException(
                        "表格行 rowId 不能为空"
                );
            }

            rowId = rowId.trim();
            cells = cells == null
                    ? List.of()
                    : List.copyOf(cells);
        }

        /**
         * 兼容没有行操作的普通表格。
         */
        public Row(
                String rowId,
                List<DisplayValue> cells
        ) {
            this(rowId, cells, null);
        }
    }

    /**
     * 通用表格行操作。
     *
     * @param type           受控操作类型
     * @param label          操作按钮名称
     * @param selectionToken 后端签发的选择凭证
     */
    public record Action(
            String type,
            String label,
            String selectionToken
    ) {

        public Action {
            if (!ACTION_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                        "不支持的表格行操作类型"
                );
            }

            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException(
                        "表格行操作名称不能为空"
                );
            }

            label = label.trim();

            if (label.length() > 32) {
                throw new IllegalArgumentException(
                        "表格行操作名称过长"
                );
            }

            if (selectionToken == null
                    || selectionToken.isBlank()) {
                throw new IllegalArgumentException(
                        "selectionToken 不能为空"
                );
            }

            selectionToken = selectionToken.trim();

            if (selectionToken.length() > 4096) {
                throw new IllegalArgumentException(
                        "selectionToken 长度超过限制"
                );
            }
        }
    }

    /**
     * 计算兼容构造器的默认分页大小。
     */
    private static int defaultPageSize(List<Row> rows) {
        if (rows == null || rows.isEmpty()) {
            return 1;
        }
        return rows.size();
    }
}