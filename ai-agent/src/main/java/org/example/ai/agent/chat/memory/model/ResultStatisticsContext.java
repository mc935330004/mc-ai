package org.example.ai.agent.chat.memory.model;

import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最近一次统计使用的字段、条件和展示单位。
 * 只保存必要上下文，不保存完整业务数据。
 */
public record ResultStatisticsContext(
        String artifactId,
        List<Long> fieldIds,
        String operation,
        String runId,
        List<Filter> filters,
        String outputUnit) {

    /**
     * 只匹配没有新字段、新项目和筛选条件的短追问。
     * 其他表达继续交给现有模型识别。
     */
    private static final Pattern SHORT_QUESTION = Pattern.compile(
            "^(?:那|那么|再|请|请问|帮我|算一下|算|求"
                    + "|它的|它们的|这项的|这些指标的|上面的|刚才的)*"
                    + "(平均值|平均|均值|最大值|最大|最高"
                    + "|最小值|最小|最低|合计|总和|总额|总计"
                    + "|去重数量|去重计数|有效值数量|计数)"
                    + "(?:是多少呢|是多少|有多少|多少|一下|呢)?$"
    );

    public ResultStatisticsContext {
        fieldIds = fieldIds == null ? List.of() : List.copyOf(fieldIds);
        filters = filters == null ? List.of() : List.copyOf(filters);
        outputUnit = outputUnit == null ? "ORIGINAL" : outputUnit;
    }

    /**
     * 统计上下文只能用于原来的结果快照。
     */
    public boolean matchesArtifact(String currentArtifactId) {
        return artifactId != null
                && !artifactId.isBlank()
                && Objects.equals(artifactId, currentArtifactId);
    }
    /**
     * 保存字段字典真实ID，不能保存模型规划时临时分配的F1、F2。
     * values保存已经转换为业务原始单位的条件值。
     */
    public record Filter(
            Long fieldId,
            String operator,
            List<String> values) {

        public Filter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }
    /**
     * 返回短追问中的运算方式，无法确定时返回null。
     */
    public String resolveShortOperation(String question) {
        if (question == null || question.isBlank() || fieldIds.isEmpty()) {
            return null;
        }

        String text = question.replaceAll("[\\s，,。！!？?：:；;]", "");

        // 上一轮统计了多个字段时，“它”不能擅自指向其中某一个。
        if (fieldIds.size() > 1
                && (text.contains("它的") || text.contains("这项的"))) {
            return null;
        }

        Matcher matcher = SHORT_QUESTION.matcher(text);
        if (!matcher.matches()) {
            return null;
        }

        return switch (matcher.group(1)) {
            case "平均值", "平均", "均值" -> "AVG";
            case "最大值", "最大", "最高" -> "MAX";
            case "最小值", "最小", "最低" -> "MIN";
            case "合计", "总和", "总额", "总计" -> "SUM";
            case "去重数量", "去重计数" -> "COUNT_DISTINCT";
            case "有效值数量", "计数" -> "COUNT";
            default -> null;
        };
    }
}