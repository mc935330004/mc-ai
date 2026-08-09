package org.example.ai.agent.common.enums;

/**
 * 报告查询类型。
 *
 * DATA_QUERY 只展示业务数据，不调用 AI 分析。
 * ANALYSIS_REPORT 先展示业务数据，再异步执行 AI 分析。
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