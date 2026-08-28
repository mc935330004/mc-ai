package org.example.ai.agent.workflow.answer.report;

import org.example.ai.agent.chat.protocol.block.CalloutBlock;
import org.example.ai.agent.chat.protocol.block.DisplayValue;
import org.example.ai.agent.chat.protocol.block.FileValue;
import org.example.ai.agent.chat.protocol.block.GroupTableBlock;
import org.example.ai.agent.chat.protocol.block.KeyValueBlock;
import org.example.ai.agent.chat.protocol.block.MetricsBlock;
import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.block.StatusBlock;
import org.example.ai.agent.chat.protocol.block.TableBlock;
import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.block.TreeTableBlock;
import org.example.ai.agent.chat.protocol.response.ReportSection;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将后台内部报告模型转换成最终Block协议。
 *
 * 本类只进行安全数据转换：
 * 1. 不查询数据库；
 * 2. 不调用大模型；
 * 3. 不生成HTML和CSS；
 * 4. 不向前端透传复杂业务对象。
 */
@Component
public class ReportBlockMapper {

    /**
     * 转换完整报告章节。
     */
    public List<ReportSection> map(ReportSchemaVO report) {
        if (report == null) {
            return List.of();
        }

        List<ReportSection> result = new ArrayList<>();
        List<ReportSchemaVO.Section> sourceSections = report.sections();

        for (int index = 0; index < sourceSections.size(); index++) {
            ReportSchemaVO.Section source = sourceSections.get(index);
            String sectionId = "report-section-" + index;

            List<ResponseBlock> blocks = mapSection(
                    source,
                    sectionId,
                    report.status()
            );

            if (!blocks.isEmpty()) {
                result.add(new ReportSection(
                        sectionId,
                        safeText(source.title()),
                        index,
                        blocks
                ));
            }
        }

        ReportSection analysisSection = mapAnalysis(
                report.analysis(),
                result.size()
        );

        if (analysisSection != null) {
            result.add(analysisSection);
        }

        return List.copyOf(result);
    }

    /**
     * 根据配置区块类型生成对应Block。
     */
    private List<ResponseBlock> mapSection(
            ReportSchemaVO.Section section,
            String sectionId,
            String reportStatus) {

        String type = safeText(section.type())
                .toUpperCase(Locale.ROOT);

        return switch (type) {
            case "KEY_VALUE" -> mapKeyValueSection(section, sectionId);
            case "METRICS" -> List.of(buildMetricsBlock(section, sectionId));
            case "TABLE" -> List.of(buildTableBlock(section, sectionId));
            case "TREE_TABLE" -> List.of(buildTreeTableBlock(section, sectionId));
            case "GROUP_TABLE" -> List.of(buildGroupTableBlock(section, sectionId));
            case "WARNINGS" -> buildDataStatusBlocks(
                    section,
                    sectionId,
                    reportStatus
            );
            default -> List.of(new CalloutBlock(
                    sectionId + "-unsupported",
                    "",
                    0,
                    BlockStatus.READY,
                    BlockSource.SYSTEM,
                    Tone.WARNING,
                    "当前报告区块类型暂不支持：" + type
            ));
        };
    }

    /**
     * 基础信息中的auditStatus单独转换为状态Block。
     */
    private List<ResponseBlock> mapKeyValueSection(ReportSchemaVO.Section section, String sectionId) {
        List<ResponseBlock> blocks = new ArrayList<>();
        List<ReportSchemaVO.Item> normalItems = new ArrayList<>();
        ReportSchemaVO.Item auditStatus = null;
        for (ReportSchemaVO.Item item : section.items()) {
            if ("auditStatus".equalsIgnoreCase(safeText(item.key()))) {
                auditStatus = item;
            } else {
                normalItems.add(item);
            }
        }
        String auditStatusValue =auditStatus == null
                        ? ""
                        : scalarText(auditStatus.value());

        // 返回数据不存在审批状态时，不生成状态区块。
        if (!auditStatusValue.isBlank()) {
            StatusView status = resolveAuditStatus(auditStatusValue);
            blocks.add(new StatusBlock(
                    sectionId + "-audit-status",
                    "",
                    0,
                    BlockStatus.READY,
                    BlockSource.BUSINESS,
                    status.code(),
                    status.label(),
                    status.tone()
            ));
        }
        if (!normalItems.isEmpty()) {
            blocks.add(new KeyValueBlock(
                    sectionId + "-key-value",
                    "",
                    1,
                    BlockStatus.READY,
                    BlockSource.BUSINESS,
                    toDisplayValues(normalItems)
            ));
        }
        return List.copyOf(blocks);
    }

    private MetricsBlock buildMetricsBlock(
            ReportSchemaVO.Section section,
            String sectionId) {

        return new MetricsBlock(
                sectionId + "-metrics",
                "",
                0,
                BlockStatus.READY,
                BlockSource.BUSINESS,
                toDisplayValues(section.items())
        );
    }

    private TableBlock buildTableBlock(
            ReportSchemaVO.Section section,
            String sectionId) {

        List<TableBlock.Column> columns = toColumns(section.columns());
        List<TableBlock.Row> rows = toRows(
                sectionId,
                section.rows(),
                columns
        );

        return new TableBlock(
                sectionId + "-table",
                "",
                0,
                BlockStatus.READY,
                BlockSource.BUSINESS,
                columns,
                rows,
                rows.size(),
                false
        );
    }

    private TreeTableBlock buildTreeTableBlock(
            ReportSchemaVO.Section section,
            String sectionId) {

        List<TableBlock.Column> columns = toColumns(section.columns());
        List<TreeTableBlock.Row> rows = toTreeRows(
                sectionId,
                section.rows(),
                columns
        );

        return new TreeTableBlock(
                sectionId + "-tree-table",
                "",
                0,
                BlockStatus.READY,
                BlockSource.BUSINESS,
                columns,
                rows,
                countTreeRows(rows)
        );
    }

    private GroupTableBlock buildGroupTableBlock(
            ReportSchemaVO.Section section,
            String sectionId) {

        List<TableBlock.Column> columns = toColumns(section.columns());
        List<GroupTableBlock.Group> groups = new ArrayList<>();
        long total = 0;

        for (int index = 0; index < section.groups().size(); index++) {
            ReportSchemaVO.Group source = section.groups().get(index);

            List<TableBlock.Row> rows = toRows(
                    sectionId + "-group-" + index,
                    source.rows(),
                    columns
            );

            total += rows.size();

            groups.add(new GroupTableBlock.Group(
                    safeIdentifier(
                            source.key(),
                            sectionId + "-group-" + index
                    ),
                    safeText(source.title()),
                    toDisplayValues(source.items()),
                    rows
            ));
        }

        return new GroupTableBlock(
                sectionId + "-group-table",
                "",
                0,
                BlockStatus.READY,
                BlockSource.BUSINESS,
                columns,
                groups,
                total,
                false
        );
    }

    /**
     * 数据完整性提示不是风险规则，使用Callout展示。
     */
    private List<ResponseBlock> buildDataStatusBlocks(
            ReportSchemaVO.Section section,
            String sectionId,
            String reportStatus) {

        List<ResponseBlock> blocks = new ArrayList<>();

        for (int index = 0; index < section.items().size(); index++) {
            ReportSchemaVO.Item item = section.items().get(index);
            String content = scalarText(item.value());

            if (content.isBlank()) {
                continue;
            }

            blocks.add(new CalloutBlock(
                    sectionId + "-status-" + index,
                    "",
                    index,
                    BlockStatus.READY,
                    BlockSource.SYSTEM,
                    resolveDataStatusTone(content, reportStatus),
                    content
            ));
        }

        return List.copyOf(blocks);
    }

    /**
     * AI分析也转换为Block，不再维护专用分析组件。
     */
    private ReportSection mapAnalysis(
            ReportSchemaVO.Analysis analysis,
            int sectionOrder) {

        if (analysis == null) {
            return null;
        }

        String status = safeText(analysis.status())
                .toUpperCase(Locale.ROOT);

        if ("NOT_REQUIRED".equals(status)) {
            return null;
        }

        List<ResponseBlock> blocks = new ArrayList<>();

        if ("PENDING".equals(status)) {
            blocks.add(new StatusBlock(
                    "report-analysis-status",
                    "",
                    0,
                    BlockStatus.READY,
                    BlockSource.SYSTEM,
                    "PENDING",
                    "AI正在分析",
                    Tone.INFO
            ));
        } else if ("CANCELLED".equals(status)) {
            blocks.add(new CalloutBlock(
                    "report-analysis-cancelled",
                    "",
                    0,
                    BlockStatus.READY,
                    BlockSource.SYSTEM,
                    Tone.MUTED,
                    safeFallback(
                            analysis.summary(),
                            "AI分析已由用户终止，基础业务数据仍然有效。"
                    )
            ));
        } else if ("FAILED".equals(status)) {
            blocks.add(new CalloutBlock(
                    "report-analysis-failed",
                    "",
                    0,
                    BlockStatus.READY,
                    BlockSource.SYSTEM,
                    Tone.WARNING,
                    safeFallback(
                            analysis.summary(),
                            "本次分析暂未完成，基础业务数据仍然可以查看。"
                    )
            ));
        } else {
            appendCompletedAnalysisBlocks(blocks, analysis);
        }

        if (blocks.isEmpty()) {
            return null;
        }

        return new ReportSection(
                "report-analysis",
                "AI分析",
                sectionOrder,
                blocks
        );
    }

    private void appendCompletedAnalysisBlocks(
            List<ResponseBlock> blocks,
            ReportSchemaVO.Analysis analysis) {

        BlockSource source = resolveAnalysisSource(analysis.source());
        int order = 0;

        if (!analysis.keyAmounts().isEmpty()) {
            List<DisplayValue> amounts = analysis.keyAmounts()
                    .stream()
                    .map(this::toKeyAmount)
                    .toList();

            blocks.add(new MetricsBlock(
                    "report-analysis-amounts",
                    "关键金额",
                    order++,
                    BlockStatus.READY,
                    source,
                    amounts
            ));
        }

        String markdown = buildAnalysisMarkdown(analysis);

        if (!markdown.isBlank()) {
            blocks.add(new TextBlock(
                    "report-analysis-text",
                    "分析结论",
                    order++,
                    BlockStatus.READY,
                    source,
                    markdown
            ));
        }

        for (int index = 0; index < analysis.warnings().size(); index++) {
            String warning = safeText(analysis.warnings().get(index));

            if (warning.isBlank()) {
                continue;
            }

            /*
             * AI提示没有风险规则证据，
             * 因此使用Callout而不是WarningsBlock。
             */
            blocks.add(new CalloutBlock(
                    "report-analysis-notice-" + index,
                    "注意事项",
                    order++,
                    BlockStatus.READY,
                    source,
                    Tone.WARNING,
                    warning
            ));
        }
    }

    private DisplayValue toKeyAmount(
            ReportSchemaVO.KeyAmount amount) {

        return new DisplayValue(
                safeIdentifier(amount.key(), "amount"),
                safeText(amount.label()),
                amount.value(),
                safeText(amount.displayValue()),
                toValueType(amount.format()),
                "",
                resolveEmphasisTone(amount.emphasis()),
                List.of()
        );
    }

    private String buildAnalysisMarkdown(
            ReportSchemaVO.Analysis analysis) {

        StringBuilder result = new StringBuilder();
        String summary = safeText(analysis.summary());

        if (!summary.isBlank()) {
            result.append(summary);
        }

        List<String> highlights = analysis.highlights()
                .stream()
                .map(this::safeText)
                .filter(value -> !value.isBlank())
                .toList();

        if (!highlights.isEmpty()) {
            if (!result.isEmpty()) {
                result.append("\n\n");
            }

            result.append("**重点**\n\n");

            for (String highlight : highlights) {
                result.append("- ")
                        .append(highlight)
                        .append('\n');
            }
        }

        return result.toString().trim();
    }

    private List<DisplayValue> toDisplayValues(
            List<ReportSchemaVO.Item> items) {

        return items.stream()
                .map(item -> toDisplayValue(
                        item.key(),
                        item.label(),
                        item.value(),
                        item.valueType(),
                        Tone.DEFAULT
                ))
                .toList();
    }

    private DisplayValue toDisplayValue(
            String key,
            String label,
            Object value,
            String type,
            Tone tone) {

        ValueType valueType = toValueType(type);
        List<FileValue> files = valueType == ValueType.FILE
                ? toFiles(value)
                : List.of();

        Object rawValue = files.isEmpty()
                ? safeScalar(value)
                : null;

        String displayValue = files.isEmpty()
                ? scalarText(value)
                : "";

        return new DisplayValue(
                safeIdentifier(key, "field"),
                safeText(label),
                rawValue,
                displayValue,
                valueType,
                "",
                tone,
                files
        );
    }

    private List<TableBlock.Column> toColumns(
            List<ReportSchemaVO.Column> columns) {

        return columns.stream()
                .map(column -> new TableBlock.Column(
                        safeIdentifier(column.key(), "column"),
                        safeText(column.label()),
                        toValueType(column.dataType()),
                        ""
                ))
                .toList();
    }

    private List<TableBlock.Row> toRows(
            String blockId,
            List<Map<String, Object>> sourceRows,
            List<TableBlock.Column> columns) {

        List<TableBlock.Row> rows = new ArrayList<>();

        for (int index = 0; index < sourceRows.size(); index++) {
            Map<String, Object> source = safeRow(sourceRows.get(index));

            rows.add(new TableBlock.Row(
                    resolveRowId(
                            source,
                            blockId + "-row-" + index
                    ),
                    toCells(source, columns, Tone.DEFAULT)
            ));
        }

        return List.copyOf(rows);
    }

    private List<TreeTableBlock.Row> toTreeRows(
            String blockId,
            List<Map<String, Object>> sourceRows,
            List<TableBlock.Column> columns) {

        List<TreeTableBlock.Row> rows = new ArrayList<>();

        for (int index = 0; index < sourceRows.size(); index++) {
            Map<String, Object> source = safeRow(sourceRows.get(index));
            String rowId = resolveRowId(
                    source,
                    blockId + "-row-" + index
            );

            Tone tone = Boolean.TRUE.equals(source.get("summary"))
                    ? Tone.INFO
                    : Tone.DEFAULT;

            rows.add(new TreeTableBlock.Row(
                    rowId,
                    toCells(source, columns, tone),
                    toTreeRows(
                            rowId,
                            childRows(source.get("children")),
                            columns
                    )
            ));
        }

        return List.copyOf(rows);
    }

    private List<DisplayValue> toCells(
            Map<String, Object> row,
            List<TableBlock.Column> columns,
            Tone tone) {

        List<DisplayValue> cells = new ArrayList<>();

        for (TableBlock.Column column : columns) {
            cells.add(toDisplayValue(
                    column.key(),
                    column.label(),
                    row.get(column.key()),
                    column.valueType().name(),
                    tone
            ));
        }

        return List.copyOf(cells);
    }

    private List<Map<String, Object>> childRows(Object value) {
        if (!(value instanceof List<?> source)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (Object item : source) {
            if (!(item instanceof Map<?, ?> sourceMap)) {
                continue;
            }

            Map<String, Object> row = new LinkedHashMap<>();

            sourceMap.forEach((key, fieldValue) -> {
                if (key != null) {
                    row.put(String.valueOf(key), fieldValue);
                }
            });

            result.add(row);
        }

        return List.copyOf(result);
    }

    private Map<String, Object> safeRow(
            Map<String, Object> row) {

        return row == null ? Map.of() : row;
    }

    private String resolveRowId(
            Map<String, Object> row,
            String fallback) {

        String rowKey = scalarText(row.get("rowKey"));
        return rowKey.isBlank()
                ? fallback
                : fallback + "-" + rowKey;
    }

    private long countTreeRows(
            List<TreeTableBlock.Row> rows) {

        long total = 0;

        for (TreeTableBlock.Row row : rows) {
            total++;
            total += countTreeRows(row.children());
        }

        return total;
    }

    private List<FileValue> toFiles(Object value) {
        if (!(value instanceof Iterable<?> source)) {
            return List.of();
        }

        List<FileValue> files = new ArrayList<>();

        for (Object item : source) {
            if (!(item instanceof ReportSchemaVO.FileValue file)) {
                continue;
            }

            files.add(new FileValue(
                    safeText(file.name()),
                    safeText(file.url())
            ));
        }

        return List.copyOf(files);
    }

    private Object safeScalar(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {

            return value;
        }

        return null;
    }

    private ValueType toValueType(String value) {
        String type = safeText(value)
                .toUpperCase(Locale.ROOT);

        return switch (type) {
            case "NUMBER", "INTEGER", "INT", "LONG",
                 "DOUBLE", "DECIMAL", "BIGDECIMAL" -> ValueType.NUMBER;
            case "AMOUNT", "MONEY" -> ValueType.AMOUNT;
            case "PERCENT", "PERCENTAGE" -> ValueType.PERCENT;
            case "DATE" -> ValueType.DATE;
            case "DATETIME" -> ValueType.DATETIME;
            case "ENUM" -> ValueType.ENUM;
            case "BOOLEAN", "BOOL" -> ValueType.BOOLEAN;
            case "FILE", "FILE_LIST" -> ValueType.FILE;
            case "LINK", "URL" -> ValueType.LINK;
            default -> ValueType.TEXT;
        };
    }

    private StatusView resolveAuditStatus(Object value) {
        String text = scalarText(value);

        return switch (text) {
            case "0", "审批中" ->
                    new StatusView("0", "审批中", Tone.INFO);
            case "1", "审批成功" ->
                    new StatusView("1", "审批成功", Tone.SUCCESS);
            case "2", "驳回" ->
                    new StatusView("2", "驳回", Tone.DANGER);
            case "3", "撤回" ->
                    new StatusView("3", "撤回", Tone.WARNING);
            case "4", "暂存" ->
                    new StatusView("4", "暂存", Tone.MUTED);
            default ->
                    new StatusView(text, text, Tone.DEFAULT);
        };
    }

    private Tone resolveDataStatusTone(
            String content,
            String reportStatus) {

        String status = safeText(reportStatus)
                .toUpperCase(Locale.ROOT);

        if ("FAILED".equals(status)
                || content.contains("失败")) {

            return Tone.DANGER;
        }

        if ("PARTIAL".equals(status)
                || content.contains("部分")
                || content.contains("缺少")) {

            return Tone.WARNING;
        }

        return Tone.SUCCESS;
    }

    private BlockSource resolveAnalysisSource(String value) {
        String source = safeText(value)
                .toUpperCase(Locale.ROOT);

        return switch (source) {
            case "AI" -> BlockSource.AI;
            case "RULE", "RULE_FALLBACK" -> BlockSource.RULE;
            default -> BlockSource.SYSTEM;
        };
    }

    private Tone resolveEmphasisTone(String value) {
        String emphasis = safeText(value)
                .toUpperCase(Locale.ROOT);

        return switch (emphasis) {
            case "DANGER" -> Tone.DANGER;
            case "WARNING" -> Tone.WARNING;
            case "SUCCESS" -> Tone.SUCCESS;
            case "PRIMARY", "INFO" -> Tone.INFO;
            default -> Tone.DEFAULT;
        };
    }

    private String scalarText(Object value) {
        Object scalar = safeScalar(value);
        return scalar == null ? "" : String.valueOf(scalar).trim();
    }

    private String safeIdentifier(
            String value,
            String fallback) {

        String result = safeText(value);
        return result.isBlank() ? fallback : result;
    }

    private String safeFallback(
            String value,
            String fallback) {

        String result = safeText(value);
        return result.isBlank() ? fallback : result;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private record StatusView(
            String code,
            String label,
            Tone tone) {
    }
}