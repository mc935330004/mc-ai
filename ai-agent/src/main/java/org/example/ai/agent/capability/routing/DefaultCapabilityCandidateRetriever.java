package org.example.ai.agent.capability.routing;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 第一阶段默认能力候选召回器。
 *
 * 当前不修改数据库、不引入新依赖，使用以下信息做初筛：
 *
 * 1. 能力名称
 * 2. 业务域
 * 3. 模块名称
 * 4. 能力描述
 * 5. capabilityCode
 * 6. OpenAPI operationId
 *
 * 中文场景采用二元字符片段匹配。
 * 例如“项目列表”会拆成：
 *
 * 项目、目列、列表
 *
 * 第三阶段接入 PGVector 后，本实现可以作为关键词召回的一部分继续保留。
 */
@Component
@RequiredArgsConstructor
public class DefaultCapabilityCandidateRetriever implements CapabilityCandidateRetriever {

    private final CapabilityRoutingPolicy routingPolicy;



    private final CapabilityDefinitionService capabilityDefinitionService;
    private final CapabilityRoutingProperties routingProperties;

    @Override
    public List<CapabilityCandidate> retrieve(String userQuestion) {
        if (!StringUtils.hasText(userQuestion)) {
            return List.of();
        }

        List<CapabilityDefinition> capabilities =
                capabilityDefinitionService.listAgentCallableCapabilities();

        if (capabilities.isEmpty()) {
            return List.of();
        }

        List<CapabilityCandidate> candidates = new ArrayList<>();

        for (CapabilityDefinition capability : capabilities) {
            if (!routingPolicy.isAllowed(userQuestion,capability)) {
                continue;
            }

            CapabilityCandidate candidate = calculateCandidate(userQuestion, capability);

            if (candidate.getRecallScore() >= routingProperties.getMinRecallScore()) {
                candidates.add(candidate);
            }
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(CapabilityCandidate::getRecallScore)
                                .reversed()
                                .thenComparing(candidate ->
                                        candidate.getCapability()
                                                .getCapabilityCode())
                )
                .limit(routingProperties.getKeywordTopK())
                .toList();
    }


    /**
     * 计算单个能力的初步召回分数。
     */
    private CapabilityCandidate calculateCandidate(String userQuestion, CapabilityDefinition capability) {

        String normalizedQuestion = normalize(userQuestion);
        String searchableText = buildSearchableText(capability);

        Set<String> questionTerms = buildBigrams(normalizedQuestion);

        Set<String> capabilityTerms =  buildBigrams(searchableText);

        List<String> matchedTerms = questionTerms.stream()
                .filter(capabilityTerms::contains)
                .limit(12)
                .toList();
        double score = 0D;

        /*
         * 二元字符召回分数。
         *
         * 分母使用用户问题片段数量，
         * 表示用户问题有多少内容能被能力描述覆盖。
         */
        if (!questionTerms.isEmpty()) {
            score += 0.65D * matchedTerms.size() / questionTerms.size();
        }

        String capabilityName = normalize(capability.getCapabilityName());

        String domain =normalize(capability.getDomain());

        String moduleName = normalize(capability.getModuleName());

        /*
         * 能力名称直接命中时给予较高加分。
         */
        if (StringUtils.hasText(capabilityName) && (normalizedQuestion.contains(capabilityName)
                || capabilityName.contains(normalizedQuestion))) {
            score += 0.20D;
        }

        /*
         * 用户问题直接包含业务域或模块名称时加分。
         */
        if (StringUtils.hasText(domain) && normalizedQuestion.contains(domain)) {
            score += 0.08D;
        }

        if (StringUtils.hasText(moduleName) && normalizedQuestion.contains(moduleName)) {
            score += 0.07D;
        }
        score = Math.min(score, 1.0D);
        return CapabilityCandidate.builder()
                .capability(capability)
                .recallScore(score)
                .keywordScore(score)
                .vectorScore(0D)
                .matchedTerms(matchedTerms)
                .sources(List.of("KEYWORD"))
                .build();
    }

    /**
     * 构建用于召回的能力文本。
     *
     * 暂时只使用现有数据库字段，不修改表结构。
     */
    private String buildSearchableText( CapabilityDefinition capability) {

        return String.join(
                " ",
                safe(capability.getCapabilityName()),
                safe(capability.getDomain()),
                safe(capability.getModuleName()),
                safe(capability.getDescription()),
                safe(capability.getCapabilityCode()),
                safe(capability.getSourceOperationId())
        );
    }

    /**
     * 构造二元字符片段。
     *
     * 英文、数字、中文都可以参与。
     * 空格和常见标点会在 normalize 中移除。
     */
    private Set<String> buildBigrams(String text) {
        Set<String> result = new LinkedHashSet<>();

        if (!StringUtils.hasText(text)) {
            return result;
        }
        if (text.length() == 1) {
            result.add(text);
            return result;
        }
        for (int i = 0; i < text.length() - 1; i++) {
            result.add(text.substring(i, i + 2));
        }
        return result;
    }

    /**
     * 文本标准化。
     */
    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，。！？；：、,.!?;:()（）\\[\\]{}]+", "")
                .trim();
    }


    private String safe(String value) {
        return value == null ? "" : value;
    }
}