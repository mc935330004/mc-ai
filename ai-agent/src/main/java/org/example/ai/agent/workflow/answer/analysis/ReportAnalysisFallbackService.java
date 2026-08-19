package org.example.ai.agent.workflow.answer.analysis;

import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 在模型不可用或结果无效时生成可验证的事实分析。
 *
 * 该服务不生成业务建议，只陈述金额、比例、数量和数据异常。
 */
@Service
public class ReportAnalysisFallbackService {

    private static final int MAX_KEY_AMOUNTS = 4;
    private static final int MAX_HIGHLIGHTS = 3;
    private static final int MAX_WARNINGS = 3;

    /**
     * 根据后端可信输入生成固定分析结果。
     */
    public ReportSchemaVO.Analysis build(ReportAnalysisInput input) {
        List<ReportAnalysisInput.Metric> metrics = input == null
                ? List.of()
                : input.metrics();

        List<ReportSchemaVO.KeyAmount> keyAmounts = buildKeyAmounts(metrics);
        List<String> highlights = buildHighlights(metrics);
        List<String> warnings = buildWarnings(input, metrics);
        String summary = buildSummary(input, keyAmounts, metrics);

        return new ReportSchemaVO.Analysis(
                "DONE",
                "RULE_FALLBACK",
                summary,
                keyAmounts,
                highlights,
                warnings
        );
    }

    private List<ReportSchemaVO.KeyAmount> buildKeyAmounts(
            List<ReportAnalysisInput.Metric> metrics) {

        List<ReportSchemaVO.KeyAmount> result = new ArrayList<>();

        for (ReportAnalysisInput.Metric metric : metrics) {
            if (!"AMOUNT".equals(metric.kind())) {
                continue;
            }
            result.add(new ReportSchemaVO.KeyAmount(
                    metric.key(),
                    metric.label(),
                    metric.value(),
                    metric.displayValue(),
                    metric.kind(),
                    resolveEmphasis(metric.value())
            ));
            if (result.size() >= MAX_KEY_AMOUNTS) {
                break;
            }
        }

        return List.copyOf(result);
    }

    private List<String> buildHighlights(
            List<ReportAnalysisInput.Metric> metrics) {

        List<String> result = new ArrayList<>();

        for (ReportAnalysisInput.Metric metric : metrics) {
            if (metric.value().signum() < 0) {
                continue;
            }
            result.add(metric.label() + "为" + metric.displayValue());
            if (result.size() >= MAX_HIGHLIGHTS) {
                break;
            }
        }

        return List.copyOf(result);
    }

    private List<String> buildWarnings(
            ReportAnalysisInput input,
            List<ReportAnalysisInput.Metric> metrics) {

        List<String> result = new ArrayList<>();

        for (ReportAnalysisInput.Metric metric : metrics) {
            if (metric.value().signum() < 0) {
                result.add(metric.label() + "为负数：" + metric.displayValue());
            } else if ("PERCENT".equals(metric.kind())
                    && metric.value().compareTo(BigDecimal.valueOf(100)) > 0) {
                result.add(metric.label() + "超过100%：" + metric.displayValue());
            }
            if (result.size() >= MAX_WARNINGS) {
                return List.copyOf(result);
            }
        }

        if (input != null) {
            for (String field : input.missingFields()) {
                result.add(field + "未提供有效数据");
                if (result.size() >= MAX_WARNINGS) {
                    break;
                }
            }
        }

        return List.copyOf(result);
    }

    private String buildSummary(
            ReportAnalysisInput input,
            List<ReportSchemaVO.KeyAmount> keyAmounts,
            List<ReportAnalysisInput.Metric> metrics) {

        if (!keyAmounts.isEmpty()) {
            ReportSchemaVO.KeyAmount first = keyAmounts.get(0);
            return "已完成报告金额检查，"
                    + first.label()
                    + "为"
                    + first.displayValue()
                    + "。";
        }

        if (!metrics.isEmpty()) {
            ReportAnalysisInput.Metric first = metrics.get(0);
            return "已完成报告数据检查，"
                    + first.label()
                    + "为"
                    + first.displayValue()
                    + "。";
        }

        String reportTitle = input == null
                ? "当前报告"
                : input.reportTitle();
        if (reportTitle == null || reportTitle.isBlank()) {
            reportTitle = "当前报告";
        }
        return reportTitle + "未提供可用于金额分析的有效数据。";
    }

    private String resolveEmphasis(BigDecimal value) {
        return value.signum() < 0 ? "DANGER" : "PRIMARY";
    }
}
