package org.example.ai.agent.capability.evaluation.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CapabilityCandidateSnapshotVO {

    private String capabilityCode;

    private String capabilityName;

    private double keywordScore;

    private double vectorScore;

    private double recallScore;

    private List<String> sources;

    private List<String> matchedTerms;
}