package org.example.ai.agent.capability.evaluation.vo;

import lombok.Data;

import java.util.List;

@Data
public class CapabilityRouteMetricsVO {

    private Long totalDecisionCount;

    private Long selectedCount;

    private Long clarifyCount;

    private Long noCandidateCount;

    private Double averageConfidence;

    private Double averageDurationMs;

    private Long reviewedCount;

    private Long reviewedCorrectCount;

    private Long reviewedWrongCount;

    /**
     * 只表示已审核样本的准确率，
     * 不能冒充全部线上请求准确率。
     */
    private Double reviewedAccuracy;

    private List<CapabilityConfusionVO> topConfusions;
}