package org.example.ai.agent.capability.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 关键词 + PGVector 混合能力召回器。
 *
 * 被 @Primary 标记后，
 * DynamicCapabilityPlanner 注入 CapabilityCandidateRetriever 时
 * 会优先使用本实现。
 *
 * PGVector 不可用时，仍保留原来的关键词召回器。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.capability-routing",
        name = "hybrid-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class HybridCapabilityCandidateRetriever
        implements CapabilityCandidateRetriever {

    private final DefaultCapabilityCandidateRetriever keywordRetriever;
    /**
     * 向量召回器是可选组件。
     *
     * VectorStore 未配置或者向量数据库临时不可用时，
     * 混合召回器会自动退化为关键词召回。
     */
    private final ObjectProvider<CapabilityVectorRetriever> vectorRetrieverProvider;
    private final CapabilityDefinitionService capabilityDefinitionService;
    private final CapabilityRoutingPolicy routingPolicy;
    private final CapabilityRoutingProperties properties;

    @Override
    public List<CapabilityCandidate> retrieve(
            String userQuestion) {

        List<CapabilityCandidate> keywordCandidates =
                keywordRetriever.retrieve(userQuestion);

        List<CapabilityVectorHit> vectorHits =
                retrieveVectorCandidates(userQuestion);

        /*
         * 只允许数据库当前仍然可调用的能力进入混合结果。
         *
         * 即使 PGVector 中残留了旧向量，
         * 这里也不会把已停用能力交给 LLM。
         */
        Map<String, CapabilityDefinition> callableMap =
                capabilityDefinitionService
                        .listAgentCallableCapabilities()
                        .stream()
                        .filter(capability ->
                                routingPolicy.isAllowed(
                                        userQuestion,
                                        capability
                                )
                        )
                        .collect(
                                Collectors.toMap(
                                        CapabilityDefinition::getCapabilityCode,
                                        Function.identity(),
                                        (first, second) -> first,
                                        LinkedHashMap::new
                                )
                        );

        Map<String, MergeState> states =
                new LinkedHashMap<>();

        for (CapabilityCandidate candidate
                : keywordCandidates) {

            String code =
                    candidate.getCapability()
                            .getCapabilityCode();

            if (!callableMap.containsKey(code)) {
                continue;
            }

            MergeState state =
                    states.computeIfAbsent(
                            code,
                            ignored -> new MergeState(
                                    callableMap.get(code)
                            )
                    );

            state.keywordScore =
                    candidate.getKeywordScore() > 0D
                            ? candidate.getKeywordScore()
                            : candidate.getRecallScore();

            state.matchedTerms =
                    candidate.getMatchedTerms();
        }

        for (CapabilityVectorHit hit : vectorHits) {
            CapabilityDefinition capability =
                    callableMap.get(
                            hit.capabilityCode()
                    );

            if (capability == null) {
                continue;
            }

            MergeState state =
                    states.computeIfAbsent(
                            hit.capabilityCode(),
                            ignored ->
                                    new MergeState(capability)
                    );

            state.vectorScore =
                    hit.score();
        }

        return states.values()
                .stream()
                .map(this::toCandidate)
                .filter(candidate ->
                        candidate.getRecallScore()
                                >= properties
                                .getMinRecallScore()
                )
                .sorted(
                        Comparator
                                .comparingDouble(
                                        CapabilityCandidate
                                                ::getRecallScore
                                )
                                .reversed()
                                .thenComparing(candidate ->
                                        candidate.getCapability()
                                                .getCapabilityCode()
                                )
                )
                .limit(properties.getTopK())
                .toList();
    }

    /**
     * 获取向量召回结果。
     *
     * 这里同时处理两种降级场景：
     *
     * 1. 没有配置 VectorStore，向量召回器不存在；
     * 2. PGVector、Embedding 服务临时不可用。
     *
     * 两种场景都不应该阻断正常业务请求，
     * 系统会继续使用关键词召回结果。
     */
    private List<CapabilityVectorHit> retrieveVectorCandidates(
            String userQuestion) {

        CapabilityVectorRetriever vectorRetriever =
                vectorRetrieverProvider.getIfAvailable();

        if (vectorRetriever == null) {
            log.debug("能力向量召回未启用，本次使用关键词召回");
            return List.of();
        }

        try {
            return vectorRetriever.retrieve(userQuestion);
        } catch (Exception exception) {
            /*
             * 向量召回失败时进行降级，
             * 不让 PGVector 或 Embedding 故障扩大为业务接口故障。
             */
            log.warn(
                    "能力向量召回失败，已降级为关键词召回: question={}, error={}",
                    userQuestion,
                    exception.getMessage()
            );

            return List.of();
        }
    }


    private CapabilityCandidate toCandidate(
            MergeState state) {

        double score =
                properties.getKeywordWeight()
                        * state.keywordScore
                        + properties.getVectorWeight()
                        * state.vectorScore;

        List<String> sources =
                new ArrayList<>();

        if (state.keywordScore > 0D) {
            sources.add("KEYWORD");
        }

        if (state.vectorScore > 0D) {
            sources.add("VECTOR");
        }

        /*
         * 两条通道都命中，说明结果更稳定。
         */
        if (state.keywordScore > 0D
                && state.vectorScore > 0D) {
            score += properties.getDualHitBonus();
        }

        score = Math.min(score, 1.0D);

        return CapabilityCandidate.builder()
                .capability(state.capability)
                .recallScore(score)
                .keywordScore(state.keywordScore)
                .vectorScore(state.vectorScore)
                .matchedTerms(state.matchedTerms)
                .sources(sources)
                .build();
    }

    private static class MergeState {

        private final CapabilityDefinition capability;

        private double keywordScore;

        private double vectorScore;

        private List<String> matchedTerms =
                new ArrayList<>();

        private MergeState(
                CapabilityDefinition capability) {
            this.capability = capability;
        }
    }
}