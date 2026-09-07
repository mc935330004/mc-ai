package org.example.ai.agent.business.person.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 多人查询的安全汇总结果。
 *
 * 只保留汇总指标、允许披露的异常人员和状态，不携带工号、选择令牌或个人明细。
 */
public record MultiPersonSummary(
        ExecutionStatus status,
        Aggregate aggregate,
        List<PersonStatus> people,
        List<AnomalyPerson> anomalyPeople,
        String narrowingInstruction) {

    public MultiPersonSummary {
        people = people == null ? List.of() : List.copyOf(people);
        anomalyPeople = anomalyPeople == null ? List.of() : List.copyOf(anomalyPeople);
    }

    public enum ExecutionStatus {
        COMPLETED,
        PARTIAL,
        CANCELLED,
        LIMIT_EXCEEDED
    }

    public enum PersonQueryStatus {
        SUCCESS,
        PARTIAL,
        FAILED,
        TIMEOUT,
        CANCELLED
    }

    /** complete=false 时金额和次数为空，避免把不完整数据伪装成正式总数。 */
    public record Aggregate(
            boolean complete,
            int successfulPeople,
            Integer tripCount,
            BigDecimal travelAmount,
            BigDecimal reimbursementAmount) {
    }

    public record PersonStatus(String displayLabel, PersonQueryStatus status) {
    }

    public record AnomalyPerson(String displayLabel, List<String> anomalyTypes) {

        public AnomalyPerson {
            anomalyTypes = anomalyTypes == null ? List.of() : List.copyOf(anomalyTypes);
        }
    }
}
