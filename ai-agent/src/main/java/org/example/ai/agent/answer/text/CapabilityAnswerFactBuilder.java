package org.example.ai.agent.answer.text;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将普通Capability执行结果转换为统一回答事实。
 *
 * ToolResult中的事实已经经过字段字典和可见性处理，
 * 本类只负责合并，不再解析原始业务响应。
 */
@Component
@RequiredArgsConstructor
public class CapabilityAnswerFactBuilder {

    private final SafeModelInputBuilder safeModelInputBuilder;
    /**
     * 合并本次业务计划中全部成功能力的事实。
     */
    public BusinessTextFacts build(List<ToolResult> toolResults) {

        List<UnifiedFactSet> factSets = collectFactSets(toolResults);

        if (factSets.isEmpty()) {
            return BusinessTextFacts.empty();
        }

        List<AnswerFact> facts = factSets.stream()
                        .flatMap(factSet ->
                                factSet.facts().stream()
                        )
                        .toList();

        boolean dataComplete = factSets.stream()
                        .allMatch(
                                UnifiedFactSet::dataComplete
                        );

        /*
         * 多个能力可能查询同一批项目，
         * 不能把每个能力的记录数量直接相加。
         */
        long totalCount = factSets.stream()
                        .mapToLong(
                                UnifiedFactSet::totalCount
                        )
                        .max()
                        .orElse(0);

        UnifiedFactSet mergedFactSet = new UnifiedFactSet(facts, dataComplete, totalCount);
        List<String> displayObjectIds = resolveProjectIds(mergedFactSet.facts());
        return new BusinessTextFacts(
                mergedFactSet,
                displayObjectIds,
                List.of(),
                List.of(),
                safeModelInputBuilder.build(mergedFactSet)
        );
    }

    /**
     * 只收集执行成功且已经生成统一事实的能力结果。
     */
    private List<UnifiedFactSet> collectFactSets(
            List<ToolResult> toolResults) {

        if (toolResults == null
                || toolResults.isEmpty()) {

            return List.of();
        }

        return toolResults.stream()
                .filter(result ->
                        result != null
                                && result.isSuccess()
                                && result.getFactSet() != null
                )
                .map(ToolResult::getFactSet)
                .toList();
    }

    /**
     * 从项目编码或项目ID字段中提取项目范围。
     *
     * 字段即使不展示给用户，
     * 只要保留在内部事实层仍可用于项目数量判断。
     */
    private List<String> resolveProjectIds(
            List<AnswerFact> facts) {

        Set<String> projectIds =
                new LinkedHashSet<>();

        for (AnswerFact fact : facts) {
            if (fact == null
                    || fact.isMissing()
                    || !isProjectIdentifier(fact)
                    || fact.getRawValue() == null) {

                continue;
            }

            String value =
                    String.valueOf(
                            fact.getRawValue()
                    ).trim();

            if (StringUtils.hasText(value)) {
                projectIds.add(value);
            }
        }

        return List.copyOf(projectIds);
    }

    /**
     * 判断字段是否表示项目唯一标识。
     */
    private boolean isProjectIdentifier(
            AnswerFact fact) {

        String fieldIdentity =
                (
                        safeText(fact.getFieldCode())
                                + safeText(fact.getFieldName())
                )
                        .toLowerCase(Locale.ROOT);

        String label =
                safeText(fact.getLabel());

        return fieldIdentity.contains("projectcode")
                || fieldIdentity.contains("projectid")
                || fieldIdentity.contains("projectno")
                || label.contains("项目编码")
                || label.contains("项目编号");
    }


    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }
}