package org.example.ai.agent.graph.model.report;

import org.example.ai.agent.common.enums.ReportSectionType;
import org.example.ai.agent.graph.model.ReportFieldBindingSpec;

import java.util.List;

/**
 * 通用报告区块配置。
 *
 * KEY_VALUE、METRICS 的字段路径相对于完整安全结果。
 * TABLE、TREE_TABLE 的字段路径相对于 rowPath 命中的单行数据。
 * GROUP_TABLE 的分组字段相对于 groupPath，明细字段相对于 detailPath。
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

        /*
         * 分组明细表配置。
         */
        String groupPath,
        String groupKeyPath,
        String groupTitleKey,
        String detailPath,
        List<ReportFieldBindingSpec> groupFields,

        List<ReportCalculationSpec> calculations,
        List<ReportFieldBindingSpec> fields) {

    public ReportSectionSpec {
        groupFields = groupFields == null
                ? List.of()
                : List.copyOf(groupFields);

        calculations = calculations == null
                ? List.of()
                : List.copyOf(calculations);

        fields = fields == null
                ? List.of()
                : List.copyOf(fields);
    }
}