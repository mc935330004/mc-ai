package org.example.ai.agent.business.report.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 三种文件格式共享的不可变逻辑报告，不携带查询、鉴权或计算行为。
 */
public record LogicalReportDocument(
        String title,
        String fileStem,
        OffsetDateTime queryTime,
        List<SourceDisclosure> sources,
        List<String> associationLabels,
        List<Metric> metrics,
        List<ReportTable> tables,
        List<StatusSection> statusSections,
        boolean complete) {

    private static final int MAX_TEXT_LENGTH = 4000;
    private static final int MAX_SOURCES = 64;
    private static final int MAX_ASSOCIATIONS = 64;
    private static final int MAX_METRICS = 128;
    private static final int MAX_TABLES = 32;
    private static final int MAX_STATUSES = 64;
    private static final int MAX_TOTAL_ROWS = 10_000;
    private static final Pattern UNSAFE_FILE_NAME = Pattern.compile(
            "[\\\\/:*?\"<>|\\p{Cntrl}]"
    );
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    public LogicalReportDocument {
        title = requiredText(title, 200, "title");
        fileStem = requiredText(fileStem, 512, "fileStem");
        queryTime = Objects.requireNonNull(queryTime, "queryTime不能为空");
        sources = copyList(sources, MAX_SOURCES, "sources");
        associationLabels = copyTextList(
                associationLabels, MAX_ASSOCIATIONS, "associationLabels"
        );
        metrics = copyList(metrics, MAX_METRICS, "metrics");
        tables = copyList(tables, MAX_TABLES, "tables");
        statusSections = copyList(statusSections, MAX_STATUSES, "statusSections");
        int totalRows = tables.stream().mapToInt(table -> table.rows().size()).sum();
        if (totalRows > MAX_TOTAL_ROWS) {
            throw new IllegalArgumentException("报告总行数最多为" + MAX_TOTAL_ROWS);
        }
    }

    /** 扩展名由渲染格式决定，文件主体移除路径、控制符和Windows保留字符。 */
    public String safeFileName(String extension) {
        String normalizedExtension = Objects.toString(extension, "")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("xlsx", "docx", "pdf").contains(normalizedExtension)) {
            throw new IllegalArgumentException("报告扩展名不受支持");
        }
        String safeStem = UNSAFE_FILE_NAME.matcher(fileStem).replaceAll("")
                .replace(".", "")
                .replaceAll("\\s+", " ")
                .strip();
        if (safeStem.isEmpty()) {
            safeStem = "report";
        }
        if (WINDOWS_RESERVED_NAMES.contains(safeStem.toUpperCase(Locale.ROOT))) {
            safeStem = "report-" + safeStem;
        }
        String suffix = '.' + normalizedExtension;
        safeStem = truncateFileStem(
                safeStem,
                255 - suffix.getBytes(StandardCharsets.UTF_8).length,
                255 - suffix.length()
        ).strip();
        return safeStem + suffix;
    }

    /** 完整性文案只由布尔状态生成，禁止注入任意错误详情。 */
    public String completenessStatement() {
        return complete
                ? "数据完整：已纳入全部授权且成功获取的数据"
                : "数据不完整：部分章节可能因权限、失败、超时或缺失而未纳入";
    }

    public record SourceDisclosure(String label, String reference) {
        public SourceDisclosure {
            label = requiredText(label, 200, "source.label");
            reference = requiredText(reference, 500, "source.reference");
        }
    }

    /** 指标保持不可变安全标量，缺失值由组装器直接省略。 */
    public record Metric(String label, Object value, String unit) {
        public Metric {
            label = requiredText(label, 200, "metric.label");
            value = requireScalar(value, false, "metric.value");
            unit = optionalText(unit, 64, "metric.unit");
        }
    }

    public record ReportTable(String title, List<String> columns, List<TableRow> rows) {
        private static final int MAX_COLUMNS = 32;
        private static final int MAX_ROWS = 10_000;

        public ReportTable {
            title = requiredText(title, 200, "table.title");
            columns = copyTextList(columns, MAX_COLUMNS, "table.columns");
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("报告表格至少包含一列");
            }
            rows = copyList(rows, MAX_ROWS, "table.rows");
            int columnCount = columns.size();
            rows.forEach(row -> {
                if (row.cells().size() != columnCount) {
                    throw new IllegalArgumentException("表格行单元格数量与列数量不一致");
                }
            });
        }
    }

    public record TableRow(List<Object> cells) {
        public TableRow {
            if (cells == null || cells.size() > ReportTable.MAX_COLUMNS) {
                throw new IllegalArgumentException("table.cells数量不合法");
            }
            List<Object> copied = new ArrayList<>(cells.size());
            cells.forEach(value -> copied.add(requireCellValue(value)));
            cells = Collections.unmodifiableList(copied);
        }
    }

    /** 章节状态只接受枚举，安全文案固定生成。 */
    public record StatusSection(String title, SectionState state) {
        public StatusSection {
            title = requiredText(title, 200, "status.title");
            state = Objects.requireNonNull(state, "status.state不能为空");
        }

        public String safeMessage() {
            return state.safeMessage();
        }
    }

    public enum SectionState {
        SUCCESS("已纳入"),
        EMPTY("暂无可用数据"),
        DENIED("因权限不足未纳入"),
        FAILED("数据查询失败，章节未纳入"),
        TIMEOUT("数据查询超时，章节未纳入");

        private final String safeMessage;

        SectionState(String safeMessage) {
            this.safeMessage = safeMessage;
        }

        public String safeMessage() {
            return safeMessage;
        }
    }

    private static Number requireNumber(Number value) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof BigDecimal)) {
            throw new IllegalArgumentException("metric.value必须是受支持的不可变数字");
        }
        return value;
    }

    private static Object requireCellValue(Object value) {
        return requireScalar(value, true, "table.cell");
    }

    private static Object requireScalar(Object value, boolean nullable, String field) {
        if (value == null) {
            if (nullable) {
                return null;
            }
            throw new IllegalArgumentException(field + "不能为空");
        }
        if (value instanceof String text) {
            return optionalText(text, MAX_TEXT_LENGTH, field);
        }
        if (value instanceof Number number) {
            return requireNumber(number);
        }
        if (value instanceof Boolean || value instanceof Character
                || value instanceof UUID || value instanceof Enum<?>
                || isJavaTimeScalar(value)) {
            return value;
        }
        throw new IllegalArgumentException(field + "只允许不可变安全标量");
    }

    private static boolean isJavaTimeScalar(Object value) {
        return "java.time".equals(value.getClass().getPackageName())
                && (value instanceof TemporalAccessor
                || value instanceof TemporalAmount
                || value instanceof ZoneId);
    }

    private static <T> List<T> copyList(List<T> source, int maximum, String field) {
        if (source == null || source.size() > maximum || source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + "数量或内容不合法");
        }
        return List.copyOf(source);
    }

    private static List<String> copyTextList(List<String> source, int maximum, String field) {
        List<String> copied = copyList(source, maximum, field);
        return copied.stream()
                .map(value -> requiredText(value, MAX_TEXT_LENGTH, field))
                .toList();
    }

    private static String requiredText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + "不能为空且长度不能超过" + maximum);
        }
        return value;
    }

    private static String optionalText(String value, int maximum, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + "长度不能超过" + maximum);
        }
        return value;
    }

    private static String truncateFileStem(
            String value,
            int maximumUtf8Bytes,
            int maximumUtf16Units) {
        StringBuilder result = new StringBuilder();
        int utf8Bytes = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (utf8Bytes + characterBytes > maximumUtf8Bytes
                    || result.length() + character.length() > maximumUtf16Units) {
                break;
            }
            result.append(character);
            utf8Bytes += characterBytes;
            index += character.length();
        }
        return result.toString();
    }
}
