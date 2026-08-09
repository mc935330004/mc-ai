package org.example.ai.agent.chat.vo;

import java.util.List;
import java.util.Map;

/**
 * 前端固定报告组件使用的统一数据结构。
 *
 * 行数据由后端提供，AI 不参与业务数据和页面布局生成。
 */
public record ReportSchemaVO(
        int schemaVersion,
        String reportId,
        String reportType,
        String queryType,
        String title,
        String subtitle,
        String status,
        boolean dataComplete,
        List<Section> sections,
        Analysis analysis,
        Meta meta) {

    /**
     * 当前报告协议版本。
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ReportSchemaVO {
        schemaVersion =schemaVersion <= 0
                        ? CURRENT_SCHEMA_VERSION
                        : schemaVersion;
        queryType =queryType == null || queryType.isBlank()
                        ? "DATA_QUERY"
                        : queryType;

        sections =sections == null ? List.of() : List.copyOf(sections);
        // AI 初始状态不接收业务数据提示。
        analysis = analysis == null ? Analysis.initial(queryType) : analysis;
        meta =meta == null ? Meta.empty() : meta;
    }

    /**
     * 报告区块。
     *
     * METRICS 表示汇总指标。
     * TABLE 表示固定表格。
     * WARNINGS 表示数据状态。
     */
    public record Section(
            String type,
            String title,
            List<Item> items,
            List<Column> columns,
            List<Map<String, Object>> rows) {

        public Section {
            items =items == null
                            ? List.of()
                            : List.copyOf(items);

            columns =columns == null
                            ? List.of()
                            : List.copyOf(columns);

            rows =rows == null
                            ? List.of()
                            : List.copyOf(rows);
        }
    }

    /**
     * 汇总指标或状态提示。
     */
    public record Item(
            String key,
            String label,
            Object value,
            String valueType) {
    }

    /**
     * 表格列定义。
     */
    public record Column(
            String key,
            String label,
            String dataType) {
    }

    /**
     * AI 分析区域。
     */
    public record Analysis(
            String status,
            String summary,
            List<String> highlights,
            List<String> warnings) {

        /**
         * 兼容原有根据查询类型创建分析状态的调用。
         */
        public static Analysis initial(String queryType) {

            boolean analysisRequired =
                    "ANALYSIS_REPORT".equalsIgnoreCase(
                            queryType
                    );
            return initial(analysisRequired);
        }

        /**
         * 根据最终分析策略创建初始状态。
         */
        public static Analysis initial(boolean analysisRequired) {

            return analysisRequired
                    ? pending()
                    : notRequired();
        }

        /**
         * 当前基础报告是否等待执行 AI 分析。
         */
        public boolean requiresExecution() {
            return "PENDING".equalsIgnoreCase(status);
        }

        /**
         * 当前报告不需要 AI 分析。
         */
        public static Analysis notRequired() {
            return new Analysis(
                    "NOT_REQUIRED",
                    "",
                    List.of(),
                    List.of()
            );
        }

        /**
         * 当前报告等待异步 AI 分析。
         */
        public static Analysis pending() {
            // 业务数据提示由报告区块维护，不能混入 AI 风险列表。
            return new Analysis(
                    "PENDING",
                    "",
                    List.of(),
                    List.of()
            );
        }
    }

    /**
     * 报告统计信息和 Artifact 引用。
     */
    public record Meta(
            long totalCount,
            long successCount,
            long partialCount,
            long failureCount,
            long skippedCount,
            long descendantTotalCount,
            long descendantSuccessCount,
            long descendantFailureCount,
            long descendantSkippedCount,
            String artifactId) {

        public static Meta empty() {
            return new Meta(
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    null
            );
        }
    }
}