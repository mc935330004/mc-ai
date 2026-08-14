package org.example.ai.agent.graph.model.report;

import org.example.ai.agent.common.enums.report.ReportAggregationType;
import org.example.ai.agent.common.enums.report.ReportCalculationOperator;

/**
 * 报告计算公式中的单个字段计算项。
 *
 * @param fieldId    字段字典ID
 * @param sourcePath 自动生成的字段绝对路径
 * @param aggregation 汇总方式
 * @param operator   与前一个计算项的运算符；第一项必须为空
 */
public record ReportCalculationTermSpec(
        Long fieldId,
        String sourcePath,
        ReportAggregationType aggregation,
        ReportCalculationOperator operator) {
}