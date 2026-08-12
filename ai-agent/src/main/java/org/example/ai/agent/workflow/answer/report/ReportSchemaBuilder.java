package org.example.ai.agent.workflow.answer.report;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.common.enums.ReportQueryType;
import org.example.ai.agent.common.enums.ReportType;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.workflow.answer.report.config.ConfigurableReportSectionBuilder;
import org.example.ai.agent.workflow.answer.report.config.ReportDefinitionResolver;
import org.example.ai.agent.workflow.answer.report.config.ResolvedReportDefinition;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContextResolver;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldPolicy;
import org.example.ai.agent.workflow.answer.WorkflowAnswerModelPayload;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPayloadFactory;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.workflow.answer.WorkflowResultTraceData;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.workflow.answer.report.template.ReportTemplate;
import org.example.ai.agent.workflow.answer.report.template.ReportTemplateRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * 根据已有安全工作流结果生成固定 ReportSchema。
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

    private final WorkflowAnswerFieldContextResolver fieldContextResolver;
    private final WorkflowAnswerPayloadFactory answerPayloadFactory;
    private final ObjectMapper objectMapper;
    private final ReportTemplateRegistry reportTemplateRegistry;
    private final ReportDefinitionResolver reportDefinitionResolver;
    private final ConfigurableReportSectionBuilder configurableReportSectionBuilder;
    /**
     * 根据工作流结果构建基础报告。
     */
    public ReportSchemaVO build(
            WorkflowExecutionOutcome outcome,
            String artifactId,
            ReportQueryType queryType) {

        ReportTemplate reportTemplate = outcome == null
                ? null
                : reportTemplateRegistry
                .find(outcome.workflowCode())
                .orElse(null);

        List<String> warnings = outcome == null
                ? new ArrayList<>()
                : buildWarnings(
                        WorkflowResultTraceData.from(
                                outcome,
                                "REPORT"
                        )
                );

        ResolvedReportDefinition configuredReport =
                resolveConfiguredReport(
                        outcome,
                        reportTemplate,
                        warnings
                );

        return buildReport(
                outcome,
                artifactId,
                queryType,
                reportTemplate,
                configuredReport,
                null,
                false,
                warnings
        );
    }

    /**
     * 根据临时编译图和临时报告定义构建预览报告。
     *
     * 草稿执行没有发布版本ID，
     * 因此字段策略由临时编译图提供。
     */
    public ReportSchemaVO buildDraft(
            WorkflowExecutionOutcome outcome,
            CompiledGraphSpec compiledGraph,
            ResolvedReportDefinition configuredReport,
            ReportQueryType queryType) {

        ReportTemplate reportTemplate = outcome == null
                ? null
                : reportTemplateRegistry
                .find(outcome.workflowCode())
                .orElse(null);

        WorkflowAnswerFieldPolicy fieldPolicy =
                fieldContextResolver.resolveDraftPolicy(
                        compiledGraph,
                        outcome == null
                                ? null
                                : outcome.result()
                );

        validateDraftReportFields(
                configuredReport,
                fieldPolicy
        );

        List<String> warnings = outcome == null
                ? new ArrayList<>()
                : buildWarnings(
                        WorkflowResultTraceData.from(
                                outcome,
                                "REPORT"
                        )
                );

        return buildReport(
                outcome,
                null,
                queryType,
                reportTemplate,
                reportTemplate == null
                        ? configuredReport
                        : null,
                fieldPolicy,
                true,
                warnings
        );
    }

    /**
     * 草稿报告只能引用本次安全结果中真实出现的字段。
     */
    private void validateDraftReportFields(
            ResolvedReportDefinition configuredReport,
            WorkflowAnswerFieldPolicy fieldPolicy) {

        if (configuredReport == null) {
            return;
        }

        for (FieldDictionary dictionary :
                configuredReport.fieldsById().values()) {

            boolean present = fieldPolicy.visibleFields()
                    .stream()
                    .anyMatch(field ->
                            java.util.Objects.equals(
                                    dictionary.getCapabilityCode(),
                                    field.capabilityCode()
                            ) && (
                                    java.util.Objects.equals(
                                            dictionary.getFieldPath(),
                                            field.fieldPath()
                                    ) || java.util.Objects.equals(
                                            dictionary.getFieldName(),
                                            field.fieldName()
                                    )
                            )
                    );

            if (!present) {
                throw new IllegalStateException(
                        "报告字段没有出现在本次运行结果中，fieldId="
                                + dictionary.getId()
                );
            }
        }
    }

    /**
     * 组装正式报告和草稿预览共用的 ReportSchema。
     */
    private ReportSchemaVO buildReport(
            WorkflowExecutionOutcome outcome,
            String artifactId,
            ReportQueryType queryType,
            ReportTemplate reportTemplate,
            ResolvedReportDefinition configuredReport,
            WorkflowAnswerFieldPolicy fieldPolicy,
            boolean previewMode,
            List<String> warnings) {

        ReportQueryType safeQueryType =queryType == null
                        ? ReportQueryType.DATA_QUERY
                        : queryType;

        if (outcome == null) {
            List<String> emptyWarnings =
                    List.of("工作流没有返回结果");
            // 工作流错误归属数据状态区，不写入 AI 分析风险。
            return new ReportSchemaVO(
                    ReportSchemaVO.CURRENT_SCHEMA_VERSION,
                    "",
                    ReportType.GENERIC_WORKFLOW_REPORT.name(),
                    safeQueryType.name(),
                    "业务查询报告",
                    "",
                    "FAILED",
                    false,
                    List.of(buildWarningSection(emptyWarnings)),
                    ReportSchemaVO.Analysis.initial(safeQueryType.name()),
                    ReportSchemaVO.Meta.empty()
            );
        }

        WorkflowResultTraceData trace =WorkflowResultTraceData.from(
                        outcome,
                        "REPORT");

        String reportId =StringUtils.hasText(artifactId)
                        ? artifactId
                        : outcome.runId();

        boolean analysisRequired = !previewMode
                && resolveAnalysisRequired(
                safeQueryType,
                configuredReport
        );
        String reportType;
        if (reportTemplate != null) {
            reportType = reportTemplate.reportType().name();
        } else if (configuredReport != null) {
            reportType = configuredReport
                    .definition()
                    .reportType()
                    .name();
        } else {
            reportType =ReportType.GENERIC_WORKFLOW_REPORT.name();
        }

        String title = configuredReport != null
                        && StringUtils.hasText(
                        configuredReport.definition() .title())
                        ? configuredReport
                          .definition()
                          .title()
                          .trim()
                        : StringUtils.hasText(
                        outcome.workflowName())
                          ? outcome.workflowName()
                          : "业务查询报告";

        String status =!trace.workflowSuccess()
                        ? "FAILED"
                        : trace.workflowDataComplete()
                          ? "BASE_READY"
                          : "PARTIAL";

        List<ReportSchemaVO.Section> sections =buildReportSections(
                outcome,
                trace,
                reportTemplate,
                configuredReport,
                fieldPolicy,
                warnings
        );

        ReportSchemaVO.Meta meta = new ReportSchemaVO.Meta(
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

        String subtitle =
                analysisRequired
                        ? "基础业务数据已展示，AI 正在分析"
                        : "业务数据查询完成";

        return new ReportSchemaVO(
                ReportSchemaVO.CURRENT_SCHEMA_VERSION,
                reportId,
                reportType,
                safeQueryType.name(),
                title,
                subtitle,
                status,
                trace.workflowDataComplete(),
                sections,
                ReportSchemaVO.Analysis.initial(analysisRequired ),
                meta
        );
    }

    /**
     *  固定指标区块顺序。
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
     * 根据固定模板生成业务区块。
     *
     * 没有模板或安全字段策略异常时，
     * 只返回指标和告警，禁止降级返回原始 JSON。
     */
    private List<ReportSchemaVO.Section> buildReportSections(
            WorkflowExecutionOutcome outcome,
            WorkflowResultTraceData trace,
            ReportTemplate reportTemplate,
            ResolvedReportDefinition configuredReport,
            WorkflowAnswerFieldPolicy fieldPolicy,
            List<String> warnings) {

        if (reportTemplate == null && configuredReport == null) {
            warnings.add("当前工作流没有固定报告模板，明细暂不展示");
            return List.of(buildMetricsSection(trace),buildWarningSection(warnings));
        }
        List<ReportSchemaVO.Section> sections = new ArrayList<>();
        try {
            WorkflowAnswerFieldPolicy policy = fieldPolicy == null
                    ? fieldContextResolver.resolvePolicy(outcome)
                    : fieldPolicy;
            if (outcome.result() != null && policy.visibleFields().isEmpty()) {
                warnings.add("字段展示策略未就绪，明细暂不展示");
                sections.add(buildMetricsSection(trace));
            } else {
                WorkflowAnswerModelPayload safePayload =answerPayloadFactory.create(outcome,policy.hiddenFieldNames() );
                JsonNode safeResult =objectMapper.valueToTree(safePayload.result());
                if (reportTemplate != null) {
                    // 已有专用模板优先，保证项目结算展示不受影响。
                    sections.addAll(reportTemplate.buildSections(safeResult));
                } else {
                    // 没有专用模板时才使用工作流发布版本中的配置报告。
                    sections.addAll(configurableReportSectionBuilder.build(configuredReport,safeResult)
                    );
                }
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "固定报告模板构建失败，runId={}，workflowCode={}，errorType={}",
                    outcome.runId(),
                    outcome.workflowCode(),
                    exception.getClass().getSimpleName()
            );
            warnings.add("固定报告模板构建失败，明细暂不展示");
            sections.add(buildMetricsSection(trace));
        }
        sections.add(buildWarningSection(warnings));
        return List.copyOf(sections);
    }

    /**
     * 根据用户意图和报告配置计算最终分析策略。
     *
     * 没有配置报告时保持原有查询类型逻辑，
     * 避免影响结算专用模板和历史工作流。
     */
    private boolean resolveAnalysisRequired(
            ReportQueryType queryType,
            ResolvedReportDefinition configuredReport) {
        if (configuredReport == null) {
            return queryType.requiresAnalysis();
        }
        return configuredReport
                .definition()
                .analysisPolicy()
                .requiresAnalysis(queryType);
    }

    /**
     * 只有不存在专用模板时才解析配置报告。
     */
    private ResolvedReportDefinition resolveConfiguredReport(
            WorkflowExecutionOutcome outcome,
            ReportTemplate reportTemplate,
            List<String> warnings) {
        if (reportTemplate != null) {
            return null;
        }
        try {
            return reportDefinitionResolver.resolve(outcome).orElse(null);
        } catch (RuntimeException exception) {
            log.warn(
                    "配置报告解析失败，runId={}，workflowCode={}，errorType={}",
                    outcome.runId(),
                    outcome.workflowCode(),
                    exception.getClass().getSimpleName()
            );
            warnings.add("报告配置无效，业务明细暂不展示");
            return null;
        }
    }
    /**
     *  构建数据状态提示。
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
     * 根据工作流执行结果生成业务数据提示。
     */
    private List<String> buildWarnings(WorkflowResultTraceData trace) {
        List<String> warnings = new ArrayList<>();
        if (!trace.workflowSuccess()) {
            warnings.add("工作流执行失败");
        }
        if (trace.partialSuccess() || !trace.workflowDataComplete()) {
            warnings.add("部分项目或明细没有成功返回");
        }
        if (!warnings.isEmpty()) {
            return warnings;
        }
        // 数据状态不能携带会过期的 AI 执行状态。
        warnings.add("基础业务数据已完整返回");
        return warnings;
    }

    /**
     * 在基础报告上追加 AI 分析结果。
     */
    public ReportSchemaVO withAnalysis(ReportSchemaVO reportSchema,ReportSchemaVO.Analysis analysis) {
        if (reportSchema == null) {
            return null;
        }
        return new ReportSchemaVO(
                reportSchema.schemaVersion(),
                reportSchema.reportId(),
                reportSchema.reportType(),
                reportSchema.queryType(),
                reportSchema.title(),
                reportSchema.subtitle(),
                reportSchema.status(),
                reportSchema.dataComplete(),
                reportSchema.sections(),
                analysis == null ? reportSchema.analysis() : analysis,
                reportSchema.meta()
        );
    }
}
