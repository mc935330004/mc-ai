package org.example.ai.agent.answer.formatter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.tool.FieldMeta;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 标准事实值格式化器。
 *
 * 第二阶段只做确定性格式化，
 * 不猜测金额单位，不修改原始数值含义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactValueFormatter {

    private final ObjectMapper objectMapper;

    /**
     * 枚举映射内容按配置文本缓存，避免表格每一行重复解析JSON。
     */
    private final Map<String, Map<String, String>> enumMappingCache = new ConcurrentHashMap<>();

    /**
     * 将 JSON 字段值转换为用户可读文本。
     */
    public String format(JsonNode value, FieldMeta field) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return nullText(field);
        }
        String displayFormat = field == null? null: field.getFormat();
        if ("enum".equalsIgnoreCase(displayFormat)) {
            return formatEnumValue(value, field);
        }
        if ("amount".equalsIgnoreCase(displayFormat) && value.isNumber()) {
            return formatNumber(value.decimalValue(), field.getPrecisionScale(), true);
        }
        if ("percent".equalsIgnoreCase( displayFormat)) {
            String percentValue = value.isNumber() ? formatNumber(value.decimalValue(),field.getPrecisionScale(),false): value.asText();
            return percentValue.endsWith("%")
                    ? percentValue
                    : percentValue + "%";
        }

        if (value.isTextual()) {
            return value.asText();
        }

        if (value.isNumber() && field != null && field.getPrecisionScale() != null) {
            return formatNumber(value.decimalValue(), field.getPrecisionScale(), false);
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }

        /*
         * 对象或数组保留 JSON 文本，
         * 但正常字段字典不建议直接配置到整个对象节点。
         */
        return value.toString();
    }

    /**
     * 将业务原始枚举值转换为字段字典配置的展示文字。
     *
     * 未命中映射时保留原始值，避免丢失新增加的业务状态。
     */
    private String formatEnumValue(
            JsonNode value,
            FieldMeta field) {

        String rawValue = value.isValueNode()
                ? value.asText()
                : value.toString();

        if (field == null
                || !StringUtils.hasText(
                field.getEnumMappingJson())) {

            return rawValue;
        }

        Map<String, String> mapping =
                enumMappingCache.computeIfAbsent(
                        field.getEnumMappingJson(),
                        this::parseEnumMapping
                );

        return mapping.getOrDefault(rawValue, rawValue);
    }

    /**
     * 解析经过后台保存校验的枚举映射。
     */
    private Map<String, String> parseEnumMapping(String enumMappingJson) {

        try {
            JsonNode root = objectMapper.readTree(enumMappingJson);
            if (!root.isObject()) {
                return Map.of();
            }
            Map<String, String> mapping = new LinkedHashMap<>();

            root.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    mapping.put(entry.getKey(), entry.getValue().asText());
                }
            });
            return Map.copyOf(mapping);
        } catch (Exception exception) {
            /*
             * 历史无效配置不能阻断整个报告，
             * 保存接口已经负责阻止新无效配置进入数据库。
             */
            log.warn("字段枚举映射解析失败，errorType={}", exception.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * 按字段配置确定性格式化数字。
     *
     * 不换算金额单位，
     * 不自动将比例乘以100。
     */
    private String formatNumber(BigDecimal value, Integer precisionScale, boolean grouping) {
        if (precisionScale == null) {
            if (grouping) {
                DecimalFormat formatter = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
                return formatter.format(value);
            }
            return value.stripTrailingZeros().toPlainString();
        }

        BigDecimal scaled =value.setScale(precisionScale, RoundingMode.HALF_UP);
        DecimalFormat formatter = new DecimalFormat(grouping ? "#,##0" : "0", DecimalFormatSymbols.getInstance(Locale.ROOT));
        formatter.setMinimumFractionDigits(precisionScale);
        formatter.setMaximumFractionDigits(precisionScale);
        return formatter.format(scaled);
    }

    /**
     * 空值只生成展示文本，不改变原始值和计算结果。
     */
    public String nullText(FieldMeta field) {
        String type = field == null || field.getType() == null ? "" : field.getType().trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "number", "integer", "int", "long",
                 "float", "double", "decimal", "bigdecimal", "numeric" -> "0";
            default -> "";
        };
    }
}