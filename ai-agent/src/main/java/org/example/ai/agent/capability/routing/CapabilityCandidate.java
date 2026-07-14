package org.example.ai.agent.capability.routing;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.capability.entity.CapabilityDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务能力候选召回结果。
 */
@Data
@Builder
public class CapabilityCandidate {

    /**
     * 完整能力定义。
     */
    private CapabilityDefinition capability;

    /**
     * 关键词和向量融合后的最终召回分数。
     */
    private double recallScore;

    /**
     * 关键词召回原始分数。
     */
    private double keywordScore;

    /**
     * 向量召回原始分数。
     */
    private double vectorScore;

    /**
     * 关键词命中片段。
     */
    @Builder.Default
    private List<String> matchedTerms =new ArrayList<>();

    /**
     * 召回来源。
     *
     * 可能包含：
     * KEYWORD
     * VECTOR
     */
    @Builder.Default
    private List<String> sources =new ArrayList<>();
}