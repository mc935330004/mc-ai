package org.example.ai.agent.graph.model.risk;

import java.math.BigDecimal;

/**
 * 风险规则中的单个判断条件。
 *
 * 支持字段与常量比较，也支持字段与字段比较。
 */
public record WorkflowRiskConditionSpec(Long leftFieldId, String leftPath, RiskOperator operator, RiskRightType rightType, String constantValue, Long rightFieldId, String rightPath, BigDecimal multiplier) {

    public WorkflowRiskConditionSpec {
        leftPath = trimToNull(leftPath);
        constantValue = trimToNull(constantValue);
        rightPath = trimToNull(rightPath);

        rightType = rightType == null
                ? RiskRightType.CONSTANT
                : rightType;

        multiplier = multiplier == null
                ? BigDecimal.ONE
                : multiplier;
    }

    /**
     * 条件运算符。
     */
    public enum RiskOperator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        CONTAINS,
        NOT_CONTAINS,
        IS_NULL,
        NOT_NULL,
        IN,
        NOT_IN
    }

    /**
     * 右侧参与比较的数据来源。
     */
    public enum RiskRightType {
        CONSTANT,
        FIELD
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}