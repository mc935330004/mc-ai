package org.example.ai.agent.capability.evaluation.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CapabilityRouteEvalResultVO {

    private String evalRunId;

    private int totalCount;

    private int passedCount;

    private int failedCount;

    private double accuracy;
}