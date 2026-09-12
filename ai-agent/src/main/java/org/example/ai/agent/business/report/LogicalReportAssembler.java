package org.example.ai.agent.business.report;

import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer.MissingValue;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.report.model.LogicalReportDocument;
import org.example.ai.agent.business.report.model.LogicalReportDocument.Metric;
import org.example.ai.agent.business.report.model.LogicalReportDocument.ReportTable;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SourceDisclosure;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;
import org.example.ai.agent.business.report.model.LogicalReportDocument.TableRow;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 在逻辑文档边界前应用导出字段策略，渲染器不再接触任何原始事实。
 */
public final class LogicalReportAssembler {

    private static final int MAX_METRIC_DEFINITIONS = 128;
    private static final int MAX_TABLES = 32;
    private static final int MAX_COLUMNS = 32;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_TOTAL_CELLS = MAX_COLUMNS * MAX_ROWS;
    private static final int MAX_FACT_DEPTH = 64;
    // 万行报告平均允许800个文本字符，覆盖常规200项目报告并限制内存占用。
    private static final int MAX_TOTAL_TEXT_CHARACTERS = 8_000_000;

    private final BusinessFactSanitizer sanitizer;

    public LogicalReportAssembler(BusinessFactSanitizer sanitizer) {
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer不能为空");
    }

    /** 只从BusinessFactSanitizer的exportFacts通道构造报告事实。 */
    public LogicalReportDocument assemble(ReportInput input) {
        Objects.requireNonNull(input, "input不能为空");
        Map<String, Object> exportMetrics = sanitizer.sanitize(
                input.metricFacts(), input.metricPolicies()
        ).exportFacts();
        List<Metric> metrics = new ArrayList<>();
        Set<String> metricCodes = new LinkedHashSet<>();
        for (MetricDefinition definition : input.metricDefinitions()) {
            if (!metricCodes.add(definition.factCode())) {
                throw new IllegalArgumentException("指标factCode不能重复");
            }
            Object value = exportMetrics.get(definition.factCode());
            if (value == null || value == MissingValue.INSTANCE) {
                continue;
            }
            metrics.add(new Metric(definition.label(), value, definition.unit()));
        }

        List<ReportTable> tables = new ArrayList<>();
        for (TableInput table : input.tables()) {
            Map<String, Object> exportShape = sanitizer.sanitize(
                    Map.of(), table.fieldPolicies()
            ).exportFacts();
            List<ColumnDefinition> columns = table.columns().stream()
                    .filter(column -> exportShape.containsKey(column.factCode()))
                    .toList();
            if (columns.isEmpty()) {
                continue;
            }
            List<TableRow> rows = table.rawRows().stream()
                    .map(rawRow -> sanitizer.sanitize(
                            rawRow, table.fieldPolicies()
                    ).exportFacts())
                    .map(exportRow -> new TableRow(columns.stream()
                            .map(column -> missingAsNull(exportRow.get(column.factCode())))
                            .toList()))
                    .toList();
            tables.add(new ReportTable(
                    table.title(),
                    columns.stream().map(ColumnDefinition::label).toList(),
                    rows
            ));
        }

        return new LogicalReportDocument(
                input.title(), input.fileStem(), input.queryTime(), input.sources(),
                input.associationLabels(), metrics, tables, input.statusSections(),
                input.complete()
        );
    }

    private Object missingAsNull(Object value) {
        return value == MissingValue.INSTANCE ? null : value;
    }

    public record ReportInput(
            String title,
            String fileStem,
            OffsetDateTime queryTime,
            List<SourceDisclosure> sources,
            List<String> associationLabels,
            Map<String, Object> metricFacts,
            List<FieldPolicy> metricPolicies,
            List<MetricDefinition> metricDefinitions,
            List<TableInput> tables,
            List<StatusSection> statusSections,
            boolean complete) {

        public ReportInput {
            sources = copyList(sources, 64, "sources");
            associationLabels = copyList(associationLabels, 64, "associationLabels");
            metricPolicies = copyList(metricPolicies, 256, "metricPolicies");
            metricDefinitions = copyList(
                    metricDefinitions, MAX_METRIC_DEFINITIONS, "metricDefinitions"
            );
            tables = copyList(tables, MAX_TABLES, "tables");
            statusSections = copyList(statusSections, 64, "statusSections");
            metricFacts = retainExportableFacts(metricFacts, metricPolicies);
            validateBudgets(metricFacts, metricDefinitions, tables);
            metricFacts = freezeMap(metricFacts, "metricFacts");
            tables = tables.stream().map(LogicalReportAssembler::freezeTable).toList();
        }
    }

    public record MetricDefinition(String factCode, String label, String unit) {
        public MetricDefinition {
            factCode = requireText(factCode, 200, "metric.factCode");
            label = requireText(label, 200, "metric.label");
            if (unit != null && unit.length() > 64) {
                throw new IllegalArgumentException("metric.unit长度不能超过64");
            }
        }
    }

    public record ColumnDefinition(String factCode, String label) {
        public ColumnDefinition {
            factCode = requireText(factCode, 200, "column.factCode");
            label = requireText(label, 200, "column.label");
        }
    }

    public record TableInput(
            String title,
            List<ColumnDefinition> columns,
            List<Map<String, Object>> rawRows,
            List<FieldPolicy> fieldPolicies) {

        public TableInput {
            title = requireText(title, 200, "table.title");
            columns = copyList(columns, MAX_COLUMNS, "table.columns");
            fieldPolicies = copyList(fieldPolicies, 256, "table.fieldPolicies");
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("table.columns不能为空");
            }
            if (rawRows == null || rawRows.size() > MAX_ROWS) {
                throw new IllegalArgumentException("table.rawRows数量不合法");
            }
            Set<String> declaredCodes = fieldPolicies.stream()
                    .filter(FieldPolicy::exportable)
                    .map(FieldPolicy::factCode)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            List<Map<String, Object>> copiedRows = new ArrayList<>(rawRows.size());
            for (Map<String, Object> row : rawRows) {
                if (row == null) {
                    throw new IllegalArgumentException("table.rawRow不能为空");
                }
                Map<String, Object> declared = new LinkedHashMap<>();
                for (String factCode : declaredCodes) {
                    if (row.containsKey(factCode)) {
                        declared.put(factCode, row.get(factCode));
                    }
                }
                copiedRows.add(Collections.unmodifiableMap(declared));
            }
            rawRows = Collections.unmodifiableList(copiedRows);
        }
    }

    private static TableInput freezeTable(TableInput table) {
        List<Map<String, Object>> rows = table.rawRows().stream()
                .map(row -> freezeMap(row, "table.rawRow"))
                .toList();
        return new TableInput(
                table.title(), table.columns(), rows, table.fieldPolicies()
        );
    }

    private static Map<String, Object> retainExportableFacts(
            Map<String, Object> source,
            List<FieldPolicy> policies) {
        Map<String, Object> declared = new LinkedHashMap<>();
        if (source == null) {
            return declared;
        }
        for (FieldPolicy policy : policies) {
            if (policy.exportable() && source.containsKey(policy.factCode())) {
                declared.put(policy.factCode(), source.get(policy.factCode()));
            }
        }
        return declared;
    }

    /** 预算先于深冻结执行，避免超大输入触发无意义的逐值复制。 */
    private static void validateBudgets(
            Map<String, Object> metricFacts,
            List<MetricDefinition> metricDefinitions,
            List<TableInput> tables) {
        long totalRows = tables.stream().mapToLong(table -> table.rawRows().size()).sum();
        if (totalRows > MAX_ROWS) {
            throw new IllegalArgumentException("报告总行数最多为" + MAX_ROWS);
        }
        long totalCells = tables.stream()
                .flatMap(table -> table.rawRows().stream())
                .mapToLong(Map::size)
                .sum();
        if (totalCells > MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("报告总单元格数最多为" + MAX_TOTAL_CELLS);
        }

        long textCharacters = 0;
        IdentityHashMap<Object, Boolean> recursionPath = new IdentityHashMap<>();
        for (MetricDefinition definition : metricDefinitions) {
            textCharacters = addTextCharacters(
                    textCharacters, definition.label(), recursionPath, 0
            );
            textCharacters = addTextCharacters(
                    textCharacters, definition.unit(), recursionPath, 0
            );
        }
        for (Object value : metricFacts.values()) {
            textCharacters = addTextCharacters(textCharacters, value, recursionPath, 0);
        }
        for (TableInput table : tables) {
            textCharacters = addTextCharacters(textCharacters, table.title(), recursionPath, 0);
            for (ColumnDefinition column : table.columns()) {
                textCharacters = addTextCharacters(
                        textCharacters, column.label(), recursionPath, 0
                );
            }
            for (Map<String, Object> row : table.rawRows()) {
                for (Object value : row.values()) {
                    textCharacters = addTextCharacters(
                            textCharacters, value, recursionPath, 0
                    );
                }
            }
        }
    }

    private static long addTextCharacters(
            long current,
            Object value,
            IdentityHashMap<Object, Boolean> recursionPath,
            int depth) {
        if (depth > MAX_FACT_DEPTH) {
            throw new IllegalArgumentException("报告事实嵌套深度不能超过" + MAX_FACT_DEPTH);
        }
        if (value == null) {
            return current;
        }
        if (value instanceof String text) {
            return checkedTextTotal(current, text.length());
        }
        if (value instanceof Character) {
            return checkedTextTotal(current, 1);
        }
        if (isBudgetScalar(value)) {
            String text = value instanceof Enum<?> enumValue
                    ? enumValue.name()
                    : String.valueOf(value);
            return checkedTextTotal(current, text.length());
        }
        if (!(value instanceof Map<?, ?> || value instanceof Collection<?>
                || value.getClass().isArray())) {
            return current;
        }
        if (recursionPath.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("报告事实存在循环引用");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        current = checkedTextTotal(current, key.length());
                    }
                    current = addTextCharacters(
                            current, entry.getValue(), recursionPath, depth + 1
                    );
                }
                return current;
            }
            if (value instanceof Collection<?> collection) {
                for (Object item : collection) {
                    current = addTextCharacters(current, item, recursionPath, depth + 1);
                }
                return current;
            }
            for (int index = 0; index < Array.getLength(value); index++) {
                current = addTextCharacters(
                        current, Array.get(value, index), recursionPath, depth + 1
                );
            }
            return current;
        } finally {
            recursionPath.remove(value);
        }
    }

    private static long checkedTextTotal(long current, int added) {
        long result = current + added;
        if (result > MAX_TOTAL_TEXT_CHARACTERS) {
            throw new IllegalArgumentException(
                    "报告文本字符总数最多为" + MAX_TOTAL_TEXT_CHARACTERS
            );
        }
        return result;
    }

    private static boolean isBudgetScalar(Object value) {
        return value instanceof Boolean
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof UUID || value instanceof Enum<?>
                || ("java.time".equals(value.getClass().getPackageName())
                && (value instanceof TemporalAccessor
                || value instanceof TemporalAmount
                || value instanceof ZoneId));
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source, String field) {
        Object frozen = ReportDatasetValidator.freezeSafeValue(
                source == null ? Map.of() : new LinkedHashMap<>(source)
        );
        if (!(frozen instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + "必须是Map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    private static <T> List<T> copyList(List<T> source, int maximum, String field) {
        if (source == null || source.size() > maximum || source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + "数量或内容不合法");
        }
        return List.copyOf(source);
    }

    private static String requireText(String value, int maximum, String field) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + "不能为空且长度不能超过" + maximum);
        }
        return value;
    }
}
