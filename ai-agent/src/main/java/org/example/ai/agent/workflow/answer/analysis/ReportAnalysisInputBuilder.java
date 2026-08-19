package org.example.ai.agent.workflow.answer.analysis;

import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从固定报告结构提取金额、比例、数量和必要上下文。
 *
 * 只读取核心指标、基础信息和分组汇总字段，
 * 不对表格明细执行求和，避免树表和合计行重复计算。
 */
@Component
public class ReportAnalysisInputBuilder {

    private static final int MAX_METRICS = 30;
    private static final int MAX_FACTS = 10;
    private static final int MAX_MISSING_FIELDS = 5;

    private static final Set<String> NUMERIC_TYPES = Set.of(
            "AMOUNT",
            "NUMBER",
            "INTEGER",
            "LONG",
            "DOUBLE",
            "DECIMAL",
            "PERCENT"
    );

    private static final Pattern AMOUNT_LABEL_PATTERN = Pattern.compile(
            ".*(金额|预算|概算|成本|费用|余额|收入|支出|回款|结算款|产值|合同额|税额|保证金).*"
    );

    private static final Pattern PERCENT_LABEL_PATTERN = Pattern.compile(
            ".*(占比|比例|率).*"
    );

    /**
     * 构建一次模型调用所需的精简输入。
     */
    public ReportAnalysisInput build(ReportSchemaVO reportSchema) {
        if (reportSchema == null) {
            return new ReportAnalysisInput("业务报告", List.of(), List.of(), List.of());
        }

        Map<String, ReportAnalysisInput.Metric> metrics = new LinkedHashMap<>();
        List<ReportAnalysisInput.Fact> facts = new ArrayList<>();
        List<String> missingFields = new ArrayList<>();

        for (int sectionIndex = 0; sectionIndex < reportSchema.sections().size(); sectionIndex++) {
            ReportSchemaVO.Section section = reportSchema.sections().get(sectionIndex);
            collectItems(
                    sectionIndex,
                    "SECTION",
                    section.title(),
                    section.items(),
                    metrics,
                    facts,
                    missingFields
            );

            for (int groupIndex = 0; groupIndex < section.groups().size(); groupIndex++) {

                ReportSchemaVO.Group group = section.groups().get(groupIndex);

                /*
                 * 同一个字段在不同项目或分组中会复用相同字段标识，
                 * 必须将分组范围加入可信指标key。
                 */
                String groupScope =
                        "GROUP_" + groupIndex
                                + ":"
                                + safeText(
                                group.key(),
                                String.valueOf(groupIndex)
                        );

                collectItems(
                        sectionIndex,
                        groupScope,
                        group.title(),
                        group.items(),
                        metrics,
                        facts,
                        missingFields
                );
            }
        }

        return new ReportAnalysisInput(
                safeText(reportSchema.title(), "业务报告"),
                List.copyOf(metrics.values()),
                facts,
                missingFields
        );
    }

    private void collectItems(
            int sectionIndex,
            String scopeKey,
            String sectionTitle,
            List<ReportSchemaVO.Item> items,
            Map<String, ReportAnalysisInput.Metric> metrics,
            List<ReportAnalysisInput.Fact> facts,
            List<String> missingFields) {

        for (ReportSchemaVO.Item item : items) {
            if (item == null || !StringUtils.hasText(item.label())) {
                continue;
            }

            String type = normalizeType(item.valueType());
            String kind = resolveKind(type, item.label());

            if (kind != null) {
                collectMetric(sectionIndex, scopeKey, sectionTitle, item, kind, metrics, missingFields);
                continue;
            }
            collectFact(item, facts);
        }
    }

    private void collectMetric(int sectionIndex, String scopeKey, String sectionTitle,
                               ReportSchemaVO.Item item, String kind, Map<String, ReportAnalysisInput.Metric> metrics, List<String> missingFields) {
        BigDecimal value = parseNumber(item.value());
        if (value == null) {
            if (missingFields.size() < MAX_MISSING_FIELDS) {
                missingFields.add(item.label().trim());
            }
            return;
        }
        if (metrics.size() >= MAX_METRICS) {
            return;
        }
        String key = "S" + sectionIndex
                        + ":"
                        + scopeKey
                        + ":"
                        + safeText(item.key(), item.label());
        String label = buildLabel(sectionTitle, item.label());
        metrics.putIfAbsent(key, new ReportAnalysisInput.Metric(key, label, value, formatValue(value, kind), kind));
    }

    private void collectFact(
            ReportSchemaVO.Item item,
            List<ReportAnalysisInput.Fact> facts) {

        if (facts.size() >= MAX_FACTS || item.value() == null) {
            return;
        }

        String value = String.valueOf(item.value()).trim();
        if (!StringUtils.hasText(value) || value.length() > 100) {
            return;
        }

        facts.add(new ReportAnalysisInput.Fact(item.label().trim(), value));
    }

    private String resolveKind(String type, String label) {
        if (!NUMERIC_TYPES.contains(type)) {
            return null;
        }
        if ("PERCENT".equals(type)
                || PERCENT_LABEL_PATTERN.matcher(label).matches()) {
            return "PERCENT";
        }
        if ("AMOUNT".equals(type)
                || AMOUNT_LABEL_PATTERN.matcher(label).matches()) {
            return "AMOUNT";
        }
        return "NUMBER";
    }

    private BigDecimal parseNumber(Object rawValue) {
        if (rawValue instanceof BigDecimal decimal) {
            return decimal;
        }
        if (rawValue instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (!(rawValue instanceof CharSequence textValue)) {
            return null;
        }

        String value = textValue.toString()
                .trim()
                .replace(",", "")
                .replace("，", "")
                .replace("￥", "")
                .replace("¥", "")
                .replace("元", "")
                .replace("%", "")
                .replaceAll("\\s+", "");

        if (!value.matches("[-+]?\\d+(\\.\\d+)?")) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatValue(BigDecimal value, String kind) {
        DecimalFormat format = new DecimalFormat("#,##0.##");
        String displayValue = format.format(value);
        return "PERCENT".equals(kind)
                ? displayValue + "%"
                : displayValue;
    }

    private String buildLabel(String sectionTitle, String itemLabel) {
        if (!StringUtils.hasText(sectionTitle)) {
            return itemLabel.trim();
        }
        return sectionTitle.trim() + " / " + itemLabel.trim();
    }

    private String normalizeType(String valueType) {
        return valueType == null
                ? ""
                : valueType.trim().toUpperCase(Locale.ROOT);
    }

    private String safeText(String value, String defaultValue) {
        return StringUtils.hasText(value)
                ? value.trim()
                : defaultValue;
    }
}
