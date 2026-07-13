package org.example.ai.agent.answer.render;

import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.answer.model.FactValidationResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性 Markdown 渲染器。
 *
 * 关键业务数据由 Java 渲染，
 * 不再依赖大模型逐字段复述。
 */
@Component
public class DeterministicMarkdownRenderer {

    /**
     * 组装最终 Markdown。
     */
    public String render(String aiSummary,List<AnswerFact> facts,FactValidationResult validation) {
        StringBuilder markdown =new StringBuilder();
        markdown.append("## 结论\n\n");
        if (StringUtils.hasText(aiSummary)) {
            markdown.append(aiSummary.trim());
        } else {
            markdown.append("已完成业务数据查询。");
        }

        markdown.append("\n\n");
        List<AnswerFact> scalarFacts =
                facts == null? List.of(): facts.stream()
                        .filter(fact ->!StringUtils.hasText(fact.getRecordPath())).toList();
        if (!scalarFacts.isEmpty()) {
            appendScalarFacts(markdown, scalarFacts);
        }

        Map<String, List<AnswerFact>> collectionFacts = groupCollectionFacts(facts);
        for (Map.Entry<String, List<AnswerFact>> entry : collectionFacts.entrySet()) {
            appendCollectionTable( markdown, entry.getValue());
        }
        appendMissingFacts( markdown,validation);
        return markdown.toString().trim();
    }

    /**
     * 渲染普通单值字段。
     */
    private void appendScalarFacts(StringBuilder markdown,List<AnswerFact> facts ) {
        markdown.append("## 关键数据\n\n");
        markdown.append("| 字段 | 数值 | 说明 |\n");
        markdown.append("|---|---:|---|\n");

        for (AnswerFact fact : facts) {
            markdown.append("| ")
                    .append(escape(fact.getLabel()))
                    .append(" | ")
                    .append(escape(fact.getDisplayValue()))
                    .append(" | ")
                    .append(escape(fact.getMeaning()))
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    /**
     * 按集合分组数组事实。
     */
    private Map<String, List<AnswerFact>>
    groupCollectionFacts(List<AnswerFact> facts) {
        Map<String, List<AnswerFact>> grouped = new LinkedHashMap<>();

        if (facts == null) {
            return grouped;
        }

        for (AnswerFact fact : facts) {
            if (!StringUtils.hasText(fact.getCollectionKey())) {
                continue;
            }
            grouped.computeIfAbsent(fact.getCollectionKey(),ignored -> new ArrayList<>()).add(fact);
        }
        return grouped;
    }

    /**
     * 将一组数组事实渲染为 Markdown 表格。
     */
    private void appendCollectionTable( StringBuilder markdown,List<AnswerFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return;
        }
        markdown.append("## 明细数据\n\n");
        /*
         * 保持字段第一次出现的顺序。
         */
        Set<String> labels =new LinkedHashSet<>();

        for (AnswerFact fact : facts) {
            labels.add(fact.getLabel());
        }
        markdown.append("| ");
        for (String label : labels) {
            markdown.append(escape(label))
                    .append(" | ");
        }
        markdown.append("\n|");
        for (int index = 0;
             index < labels.size();
             index++) {
            markdown.append("---|");
        }
        markdown.append("\n");
        /*
         * 同一个 recordPath 表示同一条业务记录。
         */
        Map<String, Map<String, AnswerFact>> rows = new LinkedHashMap<>();

        for (AnswerFact fact : facts) {
            rows.computeIfAbsent(fact.getRecordPath(),ignored -> new LinkedHashMap<>()).put(fact.getLabel(), fact);
        }

        for (Map<String, AnswerFact> row : rows.values()) {
            markdown.append("| ");
            for (String label : labels) {
                AnswerFact fact = row.get(label);
                String value = fact == null? "" : fact.getDisplayValue();
                markdown.append(escape(value))
                        .append(" | ");
            }
            markdown.append("\n");
        }
        markdown.append("\n");
    }

    /**
     * 明确展示缺失的必答字段。
     */
    private void appendMissingFacts(
            StringBuilder markdown,
            FactValidationResult validation) {
        if (validation == null || validation.isComplete()) {
            return;
        }
        markdown.append("## 数据缺失说明\n\n");
        for (AnswerFact fact : validation.getMissingFacts()) {
            markdown.append("- **")
                    .append(escape(fact.getLabel()))
                    .append("**：")
                    .append(escape( fact.getDisplayValue()))
                    .append("\n");
        }
        markdown.append("\n");
    }

    /**
     * 转义 Markdown 表格特殊字符。
     */
    private String escape(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", "<br>");
    }
}