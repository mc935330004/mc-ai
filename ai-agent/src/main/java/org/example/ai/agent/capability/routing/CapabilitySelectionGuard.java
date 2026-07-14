package org.example.ai.agent.capability.routing;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 能力选择安全闸门。
 *
 * 大模型只负责推荐，后端代码负责决定是否允许继续执行。
 *
 * 以下情况不会调用业务接口：
 *
 * 1. 模型选择了候选列表之外的能力
 * 2. 模型置信度过低
 * 3. 第一名和第二名分差过小
 * 4. 候选有多个，但模型没有返回备选结果
 */
@Component
@RequiredArgsConstructor
public class CapabilitySelectionGuard {

    private final CapabilityRoutingProperties routingProperties;

    /**
     * 检查大模型返回的能力选择结果。
     *
     * 检查不通过时，不抛出系统异常，
     * 而是把计划转换为 matched=false，让上层进入追问流程。
     */
    public DynamicCapabilityPlan guard( DynamicCapabilityPlan plan, List<CapabilityCandidate> candidates) {

        if (plan == null) {
            return unmatched(
                    null,
                    "模型没有返回能力选择结果",
                    "我还无法判断应该查询哪个业务数据，请补充具体业务对象和查询目标。" );
        }

        if (!plan.isMatched()) {
            plan.setCapabilityCode(null);
            plan.setInput(new LinkedHashMap<>());
            return plan;
        }

        if (!StringUtils.hasText(plan.getCapabilityCode())) {
            return unmatched( plan,
                    "模型没有返回 capabilityCode",
                    buildClarifyQuestion(candidates));
        }

        boolean selectedFromCandidates = candidates.stream()
                .map(CapabilityCandidate::getCapability)
                .map(CapabilityDefinition::getCapabilityCode)
                .anyMatch(plan.getCapabilityCode()::equals);

        if (!selectedFromCandidates) {
            return unmatched( plan,
                    "模型选择了候选列表之外的能力："
                            + plan.getCapabilityCode(),
                    buildClarifyQuestion(candidates));
        }

        if (plan.getConfidence() < routingProperties.getMinConfidence()) {
            return unmatched(
                    plan,
                    "能力选择置信度过低，confidence="
                            + plan.getConfidence(),
                    buildClarifyQuestion(candidates));
        }

        /*
         * 如果有多个候选，但模型没有返回备选能力，
         * 后端无法计算分差，保守地要求用户澄清。
         */
        if (candidates.size() > 1 && (plan.getAlternatives() == null || plan.getAlternatives().isEmpty())) {
            return unmatched(
                    plan,
                    "存在多个候选能力，但模型没有返回 alternatives",
                    buildClarifyQuestion(candidates)
            );
        }

        double secondScore = findSecondScore(plan.getAlternatives() );

        if (secondScore >= 0D) {
            double margin = plan.getConfidence() - secondScore;

            if (margin < routingProperties.getMinScoreMargin()) {
                return unmatched(
                        plan,
                        "第一名与第二名分差过小，margin="
                                + margin,
                        buildClarifyQuestion(candidates));
            }
        }
        return plan;
    }

    /**
     * 找出备选能力中的最高分，也就是第二名分数。
     */
    private double findSecondScore(List<CapabilityAlternative> alternatives) {

        if (alternatives == null || alternatives.isEmpty()) {
            return -1D;
        }

        return alternatives.stream()
                .map(CapabilityAlternative::getScore)
                .max(Comparator.naturalOrder())
                .orElse(-1D);
    }

    /**
     * 把模型结果转换成安全的未匹配结果。
     */
    private DynamicCapabilityPlan unmatched(
            DynamicCapabilityPlan plan,
            String reason,
            String clarifyQuestion) {

        DynamicCapabilityPlan result = plan == null
                        ? new DynamicCapabilityPlan()
                        : plan;

        result.setMatched(false);
        result.setCapabilityCode(null);
        result.setCapabilityName(null);
        result.setInput(new LinkedHashMap<>());
        result.setReason(reason);
        result.setClarifyQuestion(clarifyQuestion);

        return result;
    }

    /**
     * 根据当前候选能力生成更具体的追问。
     */
    private String buildClarifyQuestion(List<CapabilityCandidate> candidates) {

        if (candidates == null || candidates.isEmpty()) {
            return "当前没有找到明确匹配的业务接口，请补充要查询的业务对象、查询条件和期望结果。";
        }

        List<String> names = candidates.stream()
                .limit(3)
                .map(CapabilityCandidate::getCapability)
                .map(CapabilityDefinition::getCapabilityName)
                .filter(StringUtils::hasText)
                .toList();

        if (names.isEmpty()) {
            return "请补充要查询的具体业务对象和查询目标。";
        }

        return "你的问题可能涉及以下业务能力：" + String.join("、", names)
                + "。请确认你具体想查询哪一种结果。";
    }
}