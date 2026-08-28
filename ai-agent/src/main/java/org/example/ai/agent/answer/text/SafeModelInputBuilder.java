package org.example.ai.agent.answer.text;

import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 创建允许发送给大模型的安全业务事实。
 *
 * 只发送模型可见且用户可见的字段，
 * 不发送原始接口响应和隐藏字段。
 */
@Component
public class SafeModelInputBuilder {

    private static final int MAX_MODEL_FACTS = 80;

    /**
     * 创建模型输入，并明确区分业务数据完整性与模型输入裁剪。
     */
    public Map<String, Object> build(UnifiedFactSet factSet) {
        UnifiedFactSet safeFactSet = factSet == null
                ? UnifiedFactSet.empty()
                : factSet;

        // 继续复用原来的权限过滤、优先级排序和数量限制。
        List<Map<String, Object>> modelFacts = buildFacts(
                safeFactSet.facts()
        );

        // 只统计允许发送给模型的事实，不暴露隐藏字段数量。
        long availableCount = safeFactSet.facts().stream()
                .filter(this::isModelAllowed)
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordCount", safeFactSet.totalCount());
        result.put("dataComplete", safeFactSet.dataComplete());
        result.put("facts", modelFacts);

        result.put("modelAvailableFactCount", availableCount);
        result.put("modelIncludedFactCount", modelFacts.size());
        result.put(
                "modelFactsTruncated",
                availableCount > modelFacts.size()
        );

        return result;
    }

    /**
     * 筛选并排序允许发送给模型的字段。
     */
    private List<Map<String, Object>> buildFacts(
            List<AnswerFact> facts) {

        if (facts == null || facts.isEmpty()) {
            return List.of();
        }

        return facts.stream()
                .filter(Objects::nonNull)
                .filter(this::isModelAllowed)
                .sorted(
                        Comparator
                                .comparingInt(this::modelPriority)
                                .thenComparingInt(this::displayOrder)
                                .thenComparing(this::stableFieldCode)
                )
                .limit(MAX_MODEL_FACTS)
                .map(this::toModelFact)
                .toList();
    }

    /**
     * 同时满足模型可见和用户可见才能发送给模型。
     *
     * 模型输出会展示给用户，因此不能只判断modelVisible。
     */
    private boolean isModelAllowed(AnswerFact fact) {

        if (fact.isMissing()
                || !fact.isModelVisible()
                || !fact.isUserVisible()) {

            return false;
        }

        return !"HIDDEN".equalsIgnoreCase(
                fact.getDisplayComponent()
        );
    }

    /**
     * 字段优先级：
     * 用户必答字段、高重要性字段、汇总字段、普通字段、低重要性字段。
     */
    private int modelPriority(AnswerFact fact) {

        if (fact.isRequiredOutput()) {
            return 0;
        }

        if ("HIGH".equalsIgnoreCase(fact.getImportance())) {
            return 1;
        }

        if (fact.isSummary()) {
            return 2;
        }

        if ("LOW".equalsIgnoreCase(fact.getImportance())) {
            return 4;
        }

        return 3;
    }

    /**
     * 转换为精简、明确的模型事实。
     */
    private Map<String, Object> toModelFact(
            AnswerFact fact) {

        Map<String, Object> modelFact = new LinkedHashMap<>();

        putIfPresent(
                modelFact,
                "fieldCode",
                firstText(
                        fact.getFieldCode(),
                        fact.getFieldName()
                )
        );

        putIfPresent(modelFact, "label", fact.getLabel());

        /*
         * value保留真实业务值，便于模型理解数字和状态；
         * displayValue用于最终自然语言表达。
         */
        putIfPresent(modelFact, "value", fact.getRawValue());
        putIfPresent(
                modelFact,
                "displayValue",
                fact.getFormattedValue()
        );

        putIfPresent(modelFact, "meaning", fact.getMeaning());
        putIfPresent(modelFact, "group", fact.getDisplayGroup());
        putIfPresent(modelFact, "importance", fact.getImportance());
        putIfPresent(
                modelFact,
                "displayComponent",
                fact.getDisplayComponent()
        );

        putIfPresent(modelFact, "unit", fact.getUnit());

        /*
         * recordKey只用于区分多条业务记录，
         * 不允许模型将其解释为业务字段。
         */
        putIfPresent(
                modelFact,
                "recordKey",
                fact.getRecordPath()
        );

        putIfPresent(
                modelFact,
                "sourceType",
                fact.getSourceType()
        );

        return modelFact;
    }

    private int displayOrder(AnswerFact fact) {
        return fact.getDisplayOrder() == null
                ? Integer.MAX_VALUE
                : fact.getDisplayOrder();
    }

    private String stableFieldCode(
            AnswerFact fact) {

        return firstText(
                fact.getFieldCode(),
                fact.getFieldName()
        ).toLowerCase(Locale.ROOT);
    }

    private void putIfPresent(
            Map<String, Object> target,
            String key,
            Object value) {

        if (value == null) {
            return;
        }

        if (value instanceof String text
                && !StringUtils.hasText(text)) {

            return;
        }

        target.put(key, value);
    }

    private String firstText(String... values) {

        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }

        return "";
    }
}