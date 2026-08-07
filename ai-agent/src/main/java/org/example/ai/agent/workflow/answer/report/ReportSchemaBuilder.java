package org.example.ai.agent.workflow.answer.report;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContextResolver;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldPolicy;
import org.example.ai.agent.workflow.answer.WorkflowAnswerModelPayload;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPayloadFactory;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.workflow.answer.WorkflowResultTraceData;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 中文注释：根据已有安全工作流结果生成固定 ReportSchema。
 *
 * 本类只负责数据转换：
 * 1. 不访问数据库；
 * 2. 不调用大模型；
 * 3. 不生成 HTML、CSS 或 Markdown；
 * 4. 不修改 WorkflowExecutionOutcome。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportSchemaBuilder {
    /**
     * 中文注释：读取当前工作流的字段展示策略。
     */
    private final WorkflowAnswerFieldContextResolver fieldContextResolver;

    /**
     * 中文注释：复用现有安全字段过滤逻辑。
     */
    private final WorkflowAnswerPayloadFactory answerPayloadFactory;
    /**
     * 构建基础报告。
     *
     * artifactId 在 AI 分析之前可能为空，
     * 因此第一版使用 runId 作为 reportId，
     * AI完成后再重新构建一次，补充 artifactId。
     */
    public ReportSchemaVO build(WorkflowExecutionOutcome outcome, String artifactId) {
        if (outcome == null) {
            return new ReportSchemaVO(
                    "",
                    "GENERIC_WORKFLOW_REPORT",
                    "DATA_QUERY",
                    "业务查询报告",
                    "",
                    "FAILED",
                    false,
                    List.of(),
                    ReportSchemaVO.Analysis.pending(List.of("工作流没有返回结果")),
                    ReportSchemaVO.Meta.empty()
            );
        }

        /*
         * 直接复用现有统计逻辑，避免重新计算成功数、
         * 失败数和后代明细数量。
         */
        WorkflowResultTraceData trace =WorkflowResultTraceData.from(outcome,"REPORT");

        String reportId =StringUtils.hasText(artifactId)
                        ? artifactId
                        : outcome.runId();

        /*
         * 暂时使用已有 workflowCode 作为稳定 reportType，
         * 不让模型自由编造报告类型。
         *
         * 后续如果项目已有 ReportDefinitionRegistry，
         * 再将这里替换成注册表查询。
         */
        String reportType =StringUtils.hasText(outcome.workflowCode())
                        ? outcome.workflowCode()
                        .trim()
                        .toUpperCase(Locale.ROOT)
                        : "GENERIC_WORKFLOW_REPORT";

        String title =
                StringUtils.hasText(outcome.workflowName())
                        ? outcome.workflowName()
                        : "业务查询报告";

        String status =
                !trace.workflowSuccess()
                        ? "FAILED"
                        : trace.workflowDataComplete()
                        ? "BASE_READY"
                        : "PARTIAL";

        List<String> warnings =
                buildWarnings(trace);

        List<ReportSchemaVO.Section> sections =
                List.of(
                        buildMetricsSection(trace),
                        buildTableSection(outcome),
                        buildWarningSection(warnings)
                );

        ReportSchemaVO.Meta meta =
                new ReportSchemaVO.Meta(
                        trace.topLevelTotalCount(),
                        trace.topLevelSuccessCount(),
                        trace.topLevelPartialCount(),
                        trace.topLevelFailureCount(),
                        trace.topLevelSkippedCount(),
                        trace.descendantTotalCount(),
                        trace.descendantSuccessCount(),
                        trace.descendantFailureCount(),
                        trace.descendantSkippedCount(),
                        artifactId
                );

        return new ReportSchemaVO(
                reportId,
                reportType,
                "DATA_QUERY",
                title,
                "基础业务数据已先行展示",
                status,
                trace.workflowDataComplete(),
                sections,
                ReportSchemaVO.Analysis.pending(warnings),
                meta
        );
    }

    /**
     * 中文注释：固定指标区块顺序。
     */
    private ReportSchemaVO.Section buildMetricsSection(
            WorkflowResultTraceData trace) {

        List<ReportSchemaVO.Item> items =
                List.of(new ReportSchemaVO.Item(
                                "totalCount",
                                "项目总数",
                                trace.topLevelTotalCount(),
                                "NUMBER"
                        ),new ReportSchemaVO.Item(
                                "successCount",
                                "成功数量",
                                trace.topLevelSuccessCount(),
                                "NUMBER"
                        ), new ReportSchemaVO.Item(
                                "partialCount",
                                "部分成功数量",
                                trace.topLevelPartialCount(),
                                "NUMBER"
                        ),
                        new ReportSchemaVO.Item(
                                "failureCount",
                                "失败数量",
                                trace.topLevelFailureCount(),
                                "NUMBER"
                        ),
                        new ReportSchemaVO.Item(
                                "skippedCount",
                                "跳过数量",
                                trace.topLevelSkippedCount(),
                                "NUMBER"
                        ),
                        new ReportSchemaVO.Item(
                                "descendantTotalCount",
                                "明细总数",
                                trace.descendantTotalCount(),
                                "NUMBER"
                        )
                );

        return new ReportSchemaVO.Section(
                "METRICS",
                "汇总指标",
                items,
                List.of(),
                List.of()
        );
    }

    /**
     * 中文注释：
     * 使用现有字段安全策略生成结构化数据。
     *
     * 第一版只返回一个 JSON 数据区块，
     * 不擅自拆分项目和嵌套明细，
     * 避免重复计数和字段泄露。
     */
    private ReportSchemaVO.Section buildTableSection(WorkflowExecutionOutcome outcome) {

        List<ReportSchemaVO.Column> columns =List.of(
                new ReportSchemaVO.Column(
                                "data",
                                "业务数据",
                                "JSON"));

        Map<String, Object> row = new LinkedHashMap<>();
        try {
            WorkflowAnswerFieldPolicy policy =fieldContextResolver.resolvePolicy(outcome);

            /*
             * 结果存在但没有可用字段策略时，
             * 禁止直接把原始业务数据发给前端。
             */
            if (outcome.result() != null && policy.visibleFields().isEmpty()) {
                row.put( "data","字段展示策略未就绪，暂不展示明细");
            } else {
                WorkflowAnswerModelPayload safePayload =answerPayloadFactory.create(outcome,policy.hiddenFieldNames() );
                row.put("data", safePayload.result());
            }
        } catch (RuntimeException exception) {
            log.warn("报告字段安全投影失败，runId={}，已降级为提示信息",outcome.runId(), exception);
            /*
             * 字段策略加载失败时，只返回安全提示，
             * 不返回原始业务数据。
             */
            row.put("data", "字段展示策略加载失败，暂不展示明细");
        }

        return new ReportSchemaVO.Section(
                "TABLE",
                "业务数据",
                List.of(),
                columns,
                List.of(row)
        );
    }
    /**
     * 中文注释：构建数据状态提示。
     */
    private ReportSchemaVO.Section buildWarningSection(
            List<String> warnings) {

        List<ReportSchemaVO.Item> items = new ArrayList<>();

        for (int i = 0; i < warnings.size(); i++) {
            items.add(new ReportSchemaVO.Item(
                            "warning-" + i,
                            "数据状态",
                            warnings.get(i),
                            "TEXT"));
        }

        return new ReportSchemaVO.Section(
                "WARNINGS",
                "数据状态",
                items,
                List.of(),
                List.of()
        );
    }

    /**
     * 中文注释：只根据已有执行状态生成提示，不编造业务结论。
     */
    private List<String> buildWarnings( WorkflowResultTraceData trace) {
        List<String> warnings = new ArrayList<>();
        if (!trace.workflowSuccess()) {
            warnings.add("工作流执行失败");
        }
        if (trace.partialSuccess() || !trace.workflowDataComplete()) {
            warnings.add("部分项目或明细没有成功返回");
        }
        if (warnings.isEmpty()) {
            warnings.add("基础业务数据已完整返回，AI分析尚未开始");
        }
        return warnings;
    }
}