package org.example.ai.agent.capability.evaluation.vo;

import lombok.Data;

@Data
public class CapabilityConfusionVO {

    private String originalCapabilityCode;

    private String expectedCapabilityCode;

    private Long errorCount;
}