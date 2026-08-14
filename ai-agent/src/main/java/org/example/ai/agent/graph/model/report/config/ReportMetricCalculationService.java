package org.example.ai.agent.graph.model.report.config;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.report.ReportAggregationType;
import org.example.ai.agent.common.enums.report.ReportCalculationOperator;
import org.example.ai.agent.graph.model.report.ReportCalculationSpec;
import org.example.ai.agent.graph.model.report.ReportCalculationTermSpec;
import org.example.ai.agent.workflow.answer.report.config.ReportValueReader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行核心指标中的结构化计算公式。
 *
 * 只读取受限字段路径，不执行脚本、SQL、SpEL或动态方法。
 */
@Component
@RequiredArgsConstructor
public class ReportMetricCalculationService {

    /**
     * 除法中间结果保留位数。
     */
    private static final int DIVIDE_SCALE = 8;

    private final ReportValueReader valueReader;

    /**
     * 执行一个计算指标。
     *
     * 运算顺序：
     * 1. 先执行乘法和除法；
     * 2. 再执行加法和减法。
     */
    public BigDecimal calculate(
            ReportCalculationSpec calculation,
            JsonNode safeResult) {

        List<ReportCalculationTermSpec> terms =
                calculation.terms();

        if (terms.size() < 2) {
            throw new IllegalArgumentException(
                    "计算指标至少需要两个计算项"
            );
        }

        BigDecimal current = aggregate(
                terms.get(0),
                safeResult
        );

        List<BigDecimal> additiveValues =
                new ArrayList<>();

        List<ReportCalculationOperator> additiveOperators =
                new ArrayList<>();

        for (int index = 1;
             index < terms.size();
             index++) {

            ReportCalculationTermSpec term =
                    terms.get(index);

            if (term.operator() == null) {
                throw new IllegalArgumentException(
                        "第二个及后续计算项必须指定运算符"
                );
            }

            BigDecimal value = aggregate(
                    term,
                    safeResult
            );

            switch (term.operator()) {
                case MULTIPLY ->
                        current = current.multiply(value);

                case DIVIDE ->
                        current = divide(current, value);

                case ADD, SUBTRACT -> {
                    additiveValues.add(current);
                    additiveOperators.add(
                            term.operator()
                    );
                    current = value;
                }
            }
        }

        additiveValues.add(current);

        BigDecimal result = additiveValues.get(0);

        for (int index = 0;
             index < additiveOperators.size();
             index++) {

            BigDecimal value =
                    additiveValues.get(index + 1);

            ReportCalculationOperator operator =
                    additiveOperators.get(index);

            result = operator
                    == ReportCalculationOperator.ADD
                    ? result.add(value)
                    : result.subtract(value);
        }

        return result.stripTrailingZeros();
    }

    /**
     * 汇总一个字段路径命中的所有值。
     */
    private BigDecimal aggregate(
            ReportCalculationTermSpec term,
            JsonNode safeResult) {

        if (term.aggregation() == null) {
            throw new IllegalArgumentException(
                    "计算项必须指定汇总方式"
            );
        }

        List<JsonNode> nodes = valueReader.readMany(
                safeResult,
                term.sourcePath()
        );
        if (term.aggregation() == ReportAggregationType.COUNT) {
            return BigDecimal.valueOf(countScalarValues(nodes));
        }

        List<BigDecimal> values = readNumbers(nodes);

        /*
         * 字段没有返回任何有效数据时按0参与计算。
         */
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }

        return switch (term.aggregation()) {
            case SUM -> sum(values);
            case AVG -> sum(values).divide(
                    BigDecimal.valueOf(values.size()),
                    DIVIDE_SCALE,
                    RoundingMode.HALF_UP
            );

            case MAX -> values.stream()
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            case MIN -> values.stream()
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            case COUNT -> throw new IllegalStateException(
                    "COUNT已在数字解析前处理"
            );
        };
    }

    /**
     * 统计有效标量值数量。
     */
    private int countScalarValues(List<JsonNode> nodes) {
        int count = 0;
        for (JsonNode node : nodes) {
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isContainerNode()) {
                throw new IllegalArgumentException(
                        "COUNT只能统计标量字段"
                );
            }
            count++;
        }
        return count;
    }

    /**
     * 将字段值转换为BigDecimal。
     *
     * 同时兼容JSON数字和数字字符串。
     */
    private List<BigDecimal> readNumbers(List<JsonNode> nodes) {
        List<BigDecimal> values = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node == null || node.isNull()) {
                continue;
            }
            if (node.isContainerNode()) {
                throw new IllegalArgumentException(
                        "计算字段不能是对象或数组"
                );
            }
            String text = node.asText().trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                values.add(new BigDecimal(text));
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "计算字段包含非数字值"
                );
            }
        }
        return List.copyOf(values);
    }

    private BigDecimal sum(List<BigDecimal> values) {

        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            total = total.add(value);
        }
        return total;
    }

    /**
     * 执行安全除法。
     */
    private BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        if (divisor.compareTo(BigDecimal.ZERO) == 0) {
            throw new ArithmeticException(
                    "报告计算指标不能除以零"
            );
        }
        return dividend.divide(
                divisor,
                DIVIDE_SCALE,
                RoundingMode.HALF_UP
        );
    }
}