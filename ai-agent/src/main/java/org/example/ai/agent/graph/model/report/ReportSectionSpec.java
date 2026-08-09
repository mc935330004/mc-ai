package org.example.ai.agent.graph.model.report;

import org.example.ai.agent.common.enums.ReportSectionType;
import org.example.ai.agent.graph.model.ReportFieldBindingSpec;

import java.util.List;

/**
 * 通用报告区块配置。
 *
 * KEY_VALUE、METRICS 的字段路径相对于完整安全结果。
 * TABLE、TREE_TABLE 的字段路径相对于 rowPath 命中的单行数据。
 *
 * TREE_TABLE 支持两种数据结构：
 * 1. childrenPath：后台已经返回嵌套树；
 * 2. parentKeyPath：后台返回平铺列表，由报告构建器转换为树。
 */
public record ReportSectionSpec(
        ReportSectionType type,
        String title,
        String rowPath,
        String rowKeyPath,
        String childrenPath,
        String parentKeyPath,
        String rootParentValue,
        String summaryPath,
        List<ReportFieldBindingSpec> fields) {

    public ReportSectionSpec {
        fields = fields == null
                ? List.of()
                : List.copyOf(fields);
    }
}