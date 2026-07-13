package org.example.ai.agent.answer.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 标准事实完整性校验结果。
 */
@Data
@Builder
public class FactValidationResult {

    /**
     * 必答字段总数。
     */
    private int requiredCount;

    /**
     * 已获得真实值的必答字段数。
     */
    private int coveredCount;

    /**
     * 缺失必答事实。
     */
    private List<AnswerFact> missingFacts;

    /**
     * 必答字段是否全部有值。
     */
    public boolean isComplete() {
        return missingFacts == null || missingFacts.isEmpty();
    }
}