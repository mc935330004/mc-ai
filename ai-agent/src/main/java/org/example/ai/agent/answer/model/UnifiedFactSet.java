package org.example.ai.agent.answer.model;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 普通能力和工作流共用的统一事实集合。
 *
 * 不保存Markdown、页面布局或大模型生成内容。
 */
public record UnifiedFactSet(List<AnswerFact> facts, boolean dataComplete, long totalCount) {

    public UnifiedFactSet {
        facts = facts == null
                ? List.of()
                : facts.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(UnifiedFactSet::displayOrder)
                                .thenComparing(UnifiedFactSet::factKey))
                .toList();

        totalCount = Math.max(totalCount, 0);
    }

    /**
     * 根据事实列表创建统一事实集合。
     */
    public static UnifiedFactSet from(
            List<AnswerFact> facts) {

        List<AnswerFact> safeFacts =
                facts == null
                        ? List.of()
                        : facts.stream()
                        .filter(Objects::nonNull)
                        .toList();

        boolean complete =
                safeFacts.stream()
                        .filter(
                                AnswerFact::isRequiredOutput
                        )
                        .noneMatch(
                                AnswerFact::isMissing
                        );

        long recordCount =
                safeFacts.stream()
                        .filter(fact ->
                                !fact.isMissing()
                                        && hasText(
                                        fact.getRecordPath()
                                )
                        )
                        .map(
                                AnswerFact::getRecordPath
                        )
                        .distinct()
                        .count();

        /*
         * 标量结果没有recordPath，
         * 但存在有效事实时仍然表示一条业务结果。
         */
        if (recordCount == 0
                && safeFacts.stream()
                .anyMatch(fact ->
                        !fact.isMissing())) {

            recordCount = 1;
        }

        return new UnifiedFactSet(
                safeFacts,
                complete,
                recordCount
        );
    }

    /**
     * 使用业务接口返回的真实总数创建事实集合。
     *
     * 分页查询的total可能大于当前事实路径统计结果，
     * 因此由分页执行器明确传入。
     */
    public static UnifiedFactSet from(
            List<AnswerFact> facts,
            long totalCount) {

        UnifiedFactSet resolved =
                from(facts);

        return new UnifiedFactSet(
                resolved.facts(),
                resolved.dataComplete(),
                totalCount
        );
    }

    public static UnifiedFactSet empty() {
        return new UnifiedFactSet(
                List.of(),
                true,
                0
        );
    }

    private static int displayOrder(
            AnswerFact fact) {

        return fact.getDisplayOrder() == null
                ? Integer.MAX_VALUE
                : fact.getDisplayOrder();
    }

    private static String factKey(
            AnswerFact fact) {

        return fact.getFactKey() == null
                ? ""
                : fact.getFactKey();
    }

    private static boolean hasText(
            String value) {

        return value != null
                && !value.isBlank();
    }
}