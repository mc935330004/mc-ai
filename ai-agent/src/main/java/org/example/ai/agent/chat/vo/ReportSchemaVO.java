package org.example.ai.agent.chat.vo;

import java.util.List;
import java.util.Map;

/**
 * 中文注释：前端固定报告组件使用的统一数据结构。
 *
 * 注意：
 * 1. 行数据由后端提供；
 * 2. AI 不参与 HTML、Markdown 和页面布局生成；
 * 3. sections 顺序必须保持稳定。
 */
public record ReportSchemaVO(
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

    public ReportSchemaVO {
        sections = sections == null ? List.of() : List.copyOf(sections);
        analysis = analysis == null
                ? Analysis.pending(List.of())
                : analysis;
        meta = meta == null ? Meta.empty() : meta;
    }

    /**
     * 中文注释：报告区块。
     *
     * METRICS：指标卡片；
     * TABLE：固定表格；
     * WARNINGS：数据状态提示。
     */
    public record Section(
            String type,
            String title,
            List<Item> items,
            List<Column> columns,
            List<Map<String, Object>> rows) {

        public Section {
            items = items == null ? List.of() : List.copyOf(items);
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * 中文注释：指标或提示项。
     */
    public record Item(String key,String label,Object value,String valueType) {

    }

    /**
     * 中文注释：表格列定义。
     */
    public record Column(String key,String label,String dataType) {

    }

    /**
     * 中文注释：AI分析区域。
     *
     * Phase 1 只返回 PENDING，
     * Phase 2 再异步追加 summary、highlights 和 warnings。
     */
    public record Analysis( String status, String summary,List<String> highlights,List<String> warnings) {
        public static Analysis pending(List<String> warnings) {
            return new Analysis(
                    "PENDING",
                    "",
                    List.of(),
                    warnings == null ? List.of() : List.copyOf(warnings)
            );
        }
    }

    /**
     * 中文注释：报告统计信息和 Artifact 引用。
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