package org.example.ai.agent.capability.routing;

import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 能力召回阶段的统一安全策略。
 *
 * 关键词召回和向量召回必须共用同一套策略。
 */
@Component
public class CapabilityRoutingPolicy {

    private static final List<String> WRITE_KEYWORDS = List.of(
            "新增", "创建", "添加", "修改", "更新",
            "删除", "作废", "提交", "发起",
            "审批", "通过", "驳回", "保存" );

    /**
     * 判断能力是否允许进入当前问题的候选列表。
     */
    public boolean isAllowed(String userQuestion, CapabilityDefinition capability) {

        if (capability == null || !StringUtils.hasText( capability.getSideEffect())) {
            return false;
        }

        boolean writeIntent = containsAny(
                        userQuestion,
                        WRITE_KEYWORDS );

        String sideEffect = capability.getSideEffect();

        /*
         * 普通查询只能召回 READ。
         */
        if (!writeIntent) {
            return "READ".equalsIgnoreCase(sideEffect);
        }

        /*
         * 明确写操作才允许 WRITE 进入候选。
         *
         * DANGEROUS 仍可进入上层拒绝判断，
         * 但最终执行器始终禁止自动执行。
         */
        return "WRITE".equalsIgnoreCase(sideEffect) || "DANGEROUS".equalsIgnoreCase(sideEffect);
    }

    private boolean containsAny( String text, List<String> keywords) {

        if (!StringUtils.hasText(text)) {
            return false;
        }
        return keywords.stream().anyMatch(text::contains);
    }
}