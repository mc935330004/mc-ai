package org.example.ai.agent.workflow.answer.risk;

import org.example.ai.agent.graph.model.risk.WorkflowRiskRuleSpec.RiskSeverity;

import java.util.List;

/**
 * 单条规则对单个业务对象的判定结果。
 */
public record WorkflowRiskEvaluation(String ruleCode, String ruleName, RiskSeverity severity, String objectId, Status status, List<Evidence> evidence, String reason) {

    public WorkflowRiskEvaluation {
        evidence = evidence == null
                ? List.of()
                : List.copyOf(evidence);
    }

    /**
     * 风险判定采用三态结果。
     */
    public enum Status {
        MATCHED,
        NOT_MATCHED,
        UNKNOWN
    }

    /**
     * 安全判定证据。
     *
     * 只保存字段名称和截断后的展示值，
     * 不保存原始业务对象。
     */
    public record Evidence(
            Long fieldId,
            String fieldName,
            String label,
            String displayValue) {
    }
}