package org.example.ai.agent.workflow.answer.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发送给报告分析链路的精简可信数据。
 *
 * 该对象只包含报告中已经展示的字段，
 * 不包含原始工作流 JSON、字段路径和文件地址。
 */
public record ReportAnalysisInput(
        String reportTitle,
        List<Metric> metrics,
        List<Fact> facts,
        List<String> missingFields) {

    public ReportAnalysisInput {
        metrics = metrics == null ? List.of() : List.copyOf(metrics);
        facts = facts == null ? List.of() : List.copyOf(facts);
        missingFields = missingFields == null
                ? List.of()
                : List.copyOf(missingFields);
    }

    /**
     * 后端已经识别并校验的数值指标。
     */
    public record Metric(
            String key,
            String label,
            BigDecimal value,
            String displayValue,
            String kind) {
    }

    /**
     * 分析所需的少量业务上下文。
     */
    public record Fact(
            String label,
            String value) {
    }
}
