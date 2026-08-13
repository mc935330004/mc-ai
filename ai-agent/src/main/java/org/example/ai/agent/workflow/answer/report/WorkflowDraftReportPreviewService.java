package org.example.ai.agent.workflow.answer.report;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.common.enums.ReportQueryType;
import org.example.ai.agent.common.enums.WorkflowRunOrigin;
import org.example.ai.agent.common.enums.WorkflowRunStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.GraphSpecParser;
import org.example.ai.agent.graph.model.GraphSpec;
import org.example.ai.agent.workflow.answer.report.config.ReportDefinitionResolver;
import org.example.ai.agent.workflow.answer.report.config.ResolvedReportDefinition;
import org.example.ai.agent.workflow.answer.report.template.ReportTemplateRegistry;
import org.example.ai.agent.workflow.dto.WorkflowDraftReportPreviewRequestDTO;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.service.WorkflowDebugService;
import org.example.ai.agent.workflow.run.service.WorkflowRunService;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.service.WorkflowDefinitionService;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphMaterial;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphSnapshotFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 临时工作流报告预览。
 *
 * 本服务只复用已保存的安全 DEBUG 结果，
 * 不修改工作流草稿，也不再次调用业务系统。
 */
@Service
@RequiredArgsConstructor
public class WorkflowDraftReportPreviewService {

    private static final long PREVIEW_VALID_MINUTES = 30L;
    private static final int MAX_GRAPH_SPEC_BYTES =1024 * 1024;
    private final WorkflowDefinitionService workflowService;
    private final WorkflowRunService workflowRunService;
    private final WorkflowGraphSnapshotFactory snapshotFactory;
    private final GraphSpecParser graphSpecParser;
    private final ReportDefinitionResolver reportDefinitionResolver;
    private final ReportTemplateRegistry reportTemplateRegistry;
    private final ReportSchemaBuilder reportSchemaBuilder;

    /**
     * 使用临时报告定义构建 ReportSchema。
     */
    public ReportSchemaVO preview(
            Long workflowId,
            WorkflowDraftReportPreviewRequestDTO request,
            String userId) {

        String graphSpecJson =
                requireGraphSpec(request);

        WorkflowDefinition definition =
                getRequiredDefinition(workflowId);

        WorkflowRun run =
                workflowRunService.getRequiredOwned(
                        request.getRunId(),
                        userId
                );

        validateRun(
                definition,
                run
        );

        WorkflowGraphMaterial material =
                snapshotFactory.analyzeDraft(
                        definition.getWorkflowCode(),
                        definition.getWorkflowName(),
                        graphSpecJson
                );

        if (!material.valid()) {
            throw new BusinessException(
                    400,
                    "临时报告配置校验失败："
                            + material
                            .compilationResult()
                            .errors()
            );
        }

        String executionChecksum =
                snapshotFactory.executionChecksum(
                        material.normalizedGraphSpecJson()
                );

        if (!Objects.equals(
                run.getConfigChecksum(),
                executionChecksum
        )) {
            throw new BusinessException(
                    409,
                    "工作流执行结构已经变化，请重新执行样例"
            );
        }

        WorkflowExecutionOutcome outcome =
                workflowRunService.readOutcome(run);

        if (outcome == null) {
            throw new BusinessException(
                    409,
                    "临时运行结果不存在，请重新执行样例"
            );
        }

        validateOutcome(
                definition,
                outcome
        );

        GraphSpec graph =
                graphSpecParser.parse(
                        material.normalizedGraphSpecJson()
                );

        ResolvedReportDefinition reportDefinition =
                reportTemplateRegistry
                        .find(definition.getWorkflowCode())
                        .isPresent()
                        ? null
                        : reportDefinitionResolver.resolveDraft(
                                graph,
                                material
                                        .compilationResult()
                                        .compiledGraph()
                        ).orElse(null);

        return reportSchemaBuilder.buildDraft(
                outcome,
                material
                        .compilationResult()
                        .compiledGraph(),
                reportDefinition,
                ReportQueryType.DATA_QUERY
        );
    }

    /**
     * 校验临时 GraphSpec 的基础大小限制。
     */
    private String requireGraphSpec(
            WorkflowDraftReportPreviewRequestDTO request) {

        if (request == null
                || !StringUtils.hasText(
                request.getGraphSpecJson()
        )) {
            throw new BusinessException(
                    400,
                    "临时GraphSpec不能为空"
            );
        }

        String graphSpecJson =
                request.getGraphSpecJson();

        if (graphSpecJson
                .getBytes(StandardCharsets.UTF_8)
                .length > MAX_GRAPH_SPEC_BYTES) {
            throw new BusinessException(
                    400,
                    "临时GraphSpec不能超过1MB"
            );
        }

        return graphSpecJson;
    }

    /**
     * 查询当前工作流定义。
     */
    private WorkflowDefinition getRequiredDefinition(
            Long workflowId) {

        WorkflowDefinition definition =
                workflowService.getById(workflowId);

        if (definition == null) {
            throw new BusinessException(
                    404,
                    "工作流不存在：" + workflowId
            );
        }

        return definition;
    }

    /**
     * 校验运行记录来源、归属、状态和有效期。
     */
    private void validateRun(
            WorkflowDefinition definition,
            WorkflowRun run) {

        if (!Objects.equals(
                definition.getId(),
                run.getWorkflowId()
        )) {
            throw new BusinessException(
                    403,
                    "临时运行不属于当前工作流"
            );
        }

        if (!WorkflowRunOrigin.DEBUG.name()
                .equals(run.getOrigin())
                || !Objects.equals(
                WorkflowDebugService
                        .DRAFT_PREVIEW_REQUEST_PREFIX
                        + run.getRunId(),
                run.getRequestId()
        )) {
            throw new BusinessException(
                    400,
                    "当前运行不是快速配置临时运行"
            );
        }

        boolean completed =
                WorkflowRunStatus.SUCCESS.name()
                        .equals(run.getStatus())
                        || WorkflowRunStatus.PARTIAL_SUCCESS.name()
                        .equals(run.getStatus());

        if (!completed) {
            throw new BusinessException(
                    409,
                    "临时运行尚未成功完成，请重新执行样例"
            );
        }

        LocalDateTime expiredBefore =
                LocalDateTime.now()
                        .minusMinutes(
                                PREVIEW_VALID_MINUTES
                        );

        if (run.getCreatedAt() == null
                || run.getCreatedAt()
                .isBefore(expiredBefore)) {
            throw new BusinessException(
                    409,
                    "临时运行结果已过期，请重新执行样例"
            );
        }
    }

    /**
     * 校验安全结果仍属于当前工作流。
     */
    private void validateOutcome(
            WorkflowDefinition definition,
            WorkflowExecutionOutcome outcome) {

        if (!Objects.equals(
                definition.getWorkflowCode(),
                outcome.workflowCode()
        ) || !Objects.equals(
                definition.getWorkflowName(),
                outcome.workflowName()
        )) {
            throw new BusinessException(
                    409,
                    "临时运行结果与当前工作流不一致"
            );
        }
    }
}
