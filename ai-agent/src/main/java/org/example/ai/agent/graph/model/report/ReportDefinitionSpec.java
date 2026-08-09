package org.example.ai.agent.graph.model.report;

import org.example.ai.agent.common.enums.ReportAnalysisPolicy;
import org.example.ai.agent.common.enums.ReportType;

import java.util.List;

/**
 * 随工作流版本发布的报告定义。
 *
 * 报告定义只保存展示结构、结果路径、分析策略和追问策略。
 * 字段名称、类型、格式和业务含义继续读取字段字典。
 */
public record ReportDefinitionSpec(
        ReportType reportType,
        String title,
        ReportAnalysisPolicy analysisPolicy,
        ReportFollowUpSpec followUp,
        List<ReportSectionSpec> sections) {

    public ReportDefinitionSpec {

        /*
         * 旧工作流没有配置分析策略时，
         * 继续使用按查询类型决定是否分析的兼容行为。
         */
        analysisPolicy = analysisPolicy == null
                ? ReportAnalysisPolicy.ON_DEMAND
                : analysisPolicy;

        /*
         * 避免运行期间修改工作流发布快照中的区块配置。
         */
        sections = sections == null
                ? List.of()
                : List.copyOf(sections);
    }
}