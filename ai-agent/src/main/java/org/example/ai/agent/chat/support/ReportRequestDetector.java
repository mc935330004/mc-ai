package org.example.ai.agent.chat.support;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 识别明确的报告生成动作，普通业务查询仍默认使用文字回答。
 */
public final class ReportRequestDetector {

    private static final List<String> CHAT_OVERRIDE_MARKERS = List.of(
            "文字回答",
            "用文字回答",
            "只用文字回答",
            "不要报表形式",
            "不要报告形式"
    );

    /**
     * 动作和报告之间允许包含项目编号、业务名称等内容。
     * 不跨句匹配，避免把两个独立表达拼成报告请求。
     */
    private static final String REPORT_EXPRESSION =
            "(?:生成|制作|创建|形成|整理成|做成|出具|导出)"
                    + "[^，,。；;！？!?\\r\\n]*?"
                    + "(?:报告|报表)";

    private static final Pattern REPORT_REQUEST_PATTERN =
            Pattern.compile(REPORT_EXPRESSION);

    /**
     * 排除同一句中被明确否定的报告动作。
     */
    private static final Pattern NEGATED_REPORT_REQUEST_PATTERN =
            Pattern.compile(
                    "(?:不要|不用|不需要|别|无需|暂不)"
                            + "[^，,。；;！？!?\\r\\n]*?"
                            + REPORT_EXPRESSION
            );

    /**
     * 询问如何生成报告，不等于要求立即生成报告。
     */
    private static final Pattern REPORT_HELP_PATTERN =
            Pattern.compile(
                    "(?:如何|怎么|怎样)"
                            + "[^，,。；;！？!?\\r\\n]*?"
                            + REPORT_EXPRESSION
            );

    private ReportRequestDetector() {
    }

    /**
     * 明确要求文字回答时，优先保持文字模式。
     */
    public static boolean isExplicitRequest(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        // 去掉横向空白，但保留换行作为语句边界。
        String candidate = question.replaceAll("[\\t\\p{Zs}]+", "").trim();

        if (CHAT_OVERRIDE_MARKERS.stream().anyMatch(candidate::contains)) {
            return false;
        }

        candidate = NEGATED_REPORT_REQUEST_PATTERN
                .matcher(candidate)
                .replaceAll("");

        candidate = REPORT_HELP_PATTERN
                .matcher(candidate)
                .replaceAll("");

        return REPORT_REQUEST_PATTERN.matcher(candidate).find();
    }
}