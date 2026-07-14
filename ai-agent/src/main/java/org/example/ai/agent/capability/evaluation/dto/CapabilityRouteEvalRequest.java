package org.example.ai.agent.capability.evaluation.dto;

import lombok.Data;

@Data
public class CapabilityRouteEvalRequest {

    /**
     * 最大评测样本数。
     */
    private Integer limit = 200;
}