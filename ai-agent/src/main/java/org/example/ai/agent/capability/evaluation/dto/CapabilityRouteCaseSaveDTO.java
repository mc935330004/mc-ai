package org.example.ai.agent.capability.evaluation.dto;

import lombok.Data;

import java.util.Map;

@Data
public class CapabilityRouteCaseSaveDTO {

    private Long id;

    private String userQuestion;

    private String expectedRouteType;

    private String expectedCapabilityCode;

    private Boolean shouldClarify;

    private Map<String, Object> expectedInput;

    private String tags;

    private Integer enabled;
}