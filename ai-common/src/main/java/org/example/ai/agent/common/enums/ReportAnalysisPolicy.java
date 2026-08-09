package org.example.ai.agent.common.enums;

/**
 * 报告 AI 分析策略。
 *
 * ON_DEMAND：仅用户明确要求分析时执行。
 * ALWAYS：基础报告生成后自动执行。
 * DISABLED：禁止执行 AI 分析。
 */
public enum ReportAnalysisPolicy {

    ON_DEMAND,

    ALWAYS,

    DISABLED;

    /**
     * 根据报告策略和用户查询意图判断是否执行 AI 分析。
     */
    public boolean requiresAnalysis(ReportQueryType queryType) {
        if (this == DISABLED) {
            return false;
        }
        if (this == ALWAYS) {
            return true;
        }
        return queryType != null && queryType.requiresAnalysis();
    }
}