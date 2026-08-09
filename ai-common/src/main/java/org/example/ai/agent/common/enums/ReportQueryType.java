package org.example.ai.agent.common.enums;

/**
 * 报告查询意图类型。
 *
 * DATA_QUERY 表示用户主要请求查询业务数据。
 * ANALYSIS_REPORT 表示用户明确请求分析、风险或建议。
 *
 * 是否最终执行 AI 分析，还需要结合报告的
 * ReportAnalysisPolicy 判断。
 */
public enum ReportQueryType {

    DATA_QUERY,

    ANALYSIS_REPORT;

    /**
     * 判断当前查询是否需要 AI 分析。
     */
    public boolean requiresAnalysis() {
        return this == ANALYSIS_REPORT;
    }
}