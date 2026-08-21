package org.example.ai.agent.graph.model.risk;

import java.util.List;

/**
 * 随工作流版本发布的风险规则。
 *
 * 路径由前端根据字段字典自动生成，
 * 后台发布时必须再次校验字段和路径。
 */
public record WorkflowRiskRuleSpec(String code, String name, RiskSeverity severity, RiskLogic logic,
                                   Long objectKeyFieldId, String objectPath, boolean enabled,
                                   List<WorkflowRiskConditionSpec> conditions) {

    public WorkflowRiskRuleSpec {
        code = trimToNull(code);
        name = trimToNull(name);
        objectPath = trimToNull(objectPath);

        severity = severity == null
                ? RiskSeverity.MEDIUM
                : severity;

        logic = logic == null
                ? RiskLogic.AND
                : logic;

        conditions = conditions == null
                ? List.of()
                : List.copyOf(conditions);
    }

    /**
     * 风险严重程度。
     */
    public enum RiskSeverity {
        LOW,
        MEDIUM,
        HIGH
    }

    /**
     * 多个条件之间的组合方式。
     */
    public enum RiskLogic {
        AND,
        OR
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }
}