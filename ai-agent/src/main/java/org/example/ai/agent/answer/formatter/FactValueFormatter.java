package org.example.ai.agent.answer.formatter;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.tool.FieldMeta;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;

/**
 * 标准事实值格式化器。
 *
 * 第二阶段只做确定性格式化，
 * 不猜测金额单位，不修改原始数值含义。
 */
@Component
public class FactValueFormatter {

    /**
     * 将 JSON 字段值转换为用户可读文本。
     */
    public String format(JsonNode value, FieldMeta field) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return nullText(field);
        }
        String displayFormat = field == null? null: field.getFormat();

        if ("amount".equalsIgnoreCase(displayFormat) && value.isNumber()) {
            return formatAmount(value.decimalValue());
        }

        if ("percent".equalsIgnoreCase(displayFormat)) {
            /*
             * 不自动乘以100。
             *
             * 原因：
             * 无法确定业务接口返回的是 0.72 还是 72，
             * 字段字典未明确前不能擅自改变业务值。
             */
            return value.asText() + "%";
        }

        if (value.isTextual()) {
            return value.asText();
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
     * 金额只增加千位分隔符，不添加未知币种。
     */
    private String formatAmount(BigDecimal amount) {
        DecimalFormat formatter = new DecimalFormat("#,##0.##");
        return formatter.format(amount);
    }

    /**
     * 获取字段空值展示文本。
     */
    public String nullText(FieldMeta field) {
        if (field != null && StringUtils.hasText(field.getNullDisplayText() )) {
            return field.getNullDisplayText();
        }
        return "当前数据中未提供";
    }
}