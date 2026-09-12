package org.example.ai.agent.business.report;

import java.math.BigDecimal;

/** 将已验证的安全标量转换为跨格式一致的文本。 */
final class ReportScalarFormatter {

    private ReportScalarFormatter() {
    }

    static String format(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        return String.valueOf(value);
    }
}
