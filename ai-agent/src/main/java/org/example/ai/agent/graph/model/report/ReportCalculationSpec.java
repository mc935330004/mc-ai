package org.example.ai.agent.graph.model.report;

import java.util.List;

/**
 * 核心指标区块中的自定义计算指标。
 *
 * @param key           前端使用的稳定字段标识
 * @param label         自定义显示名称
 * @param displayFormat 展示格式：NUMBER、AMOUNT、PERCENT
 * @param terms         计算项
 */
public record ReportCalculationSpec(String key, String label, String displayFormat, List<ReportCalculationTermSpec> terms) {

    public ReportCalculationSpec {
        displayFormat = displayFormat == null
                || displayFormat.isBlank()
                ? "NUMBER"
                : displayFormat.trim().toUpperCase();

        terms = terms == null
                ? List.of()
                : List.copyOf(terms);
    }
}