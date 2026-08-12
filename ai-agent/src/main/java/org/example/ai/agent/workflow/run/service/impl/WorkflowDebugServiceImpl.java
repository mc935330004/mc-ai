package org.example.ai.agent.workflow.run.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.parameter.CapabilityInputSchemaValidator;
import org.example.ai.agent.capability.parameter.CapabilityInputValidationResult;
import org.example.ai.agent.common.enums.WorkflowRunOrigin;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.runtime.GraphExecutionRequest;
import org.example.ai.agent.graph.runtime.GraphExecutionResult;
import org.example.ai.agent.graph.runtime.GraphSpecRuntimeExecutor;
import org.example.ai.agent.workflow.dto.WorkflowDebugRequestDTO;
import org.example.ai.agent.workflow.dto.WorkflowDraftPreviewRequestDTO;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.run.model.WorkflowRunStartCommand;
import org.example.ai.agent.workflow.run.service.WorkflowDebugService;
import org.example.ai.agent.workflow.run.service.WorkflowRunService;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcomeFactory;
import org.example.ai.agent.workflow.service.WorkflowDefinitionService;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphMaterial;
import org.example.ai.agent.workflow.snapshot.WorkflowGraphSnapshotFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 工作流草稿调试。
 *
 * 数据库草稿调试和前端临时草稿预览共用执行链，
 * 但不会修改工作流草稿或发布版本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDebugServiceImpl
        implements WorkflowDebugService {

    /**
     * 临时 GraphSpec 最大字节数。
     */
    private static final int MAX_GRAPH_SPEC_BYTES =
            1024 * 1024;

    private final WorkflowDefinitionService workflowService;
    private final WorkflowGraphSnapshotFactory snapshotFactory;
    private final CapabilityInputSchemaValidator inputValidator;
    private final GraphSpecRuntimeExecutor graphExecutor;
    private final WorkflowExecutionOutcomeFactory outcomeFactory;
    private final WorkflowRunService workflowRunService;
    private final ObjectMapper objectMapper;

    /**
     * 调试数据库中已经保存的工作流草稿。
     */
    @Override
    public WorkflowExecutionOutcome debug(
            Long workflowId,
            WorkflowDebugRequestDTO request,
            String userId,
            String authorization) {

        WorkflowDefinition definition =
                getRequiredDefinition(workflowId);

        Map<String, Object> rawInput =
                request == null
                        ? Map.of()
                        : safeMap(request.getInput());

        Map<String, Object> userContext =
                request == null
                        ? Map.of()
                        : safeMap(request.getUserContext());

        return execute(
                definition,
                definition.getGraphSpecJson(),
                rawInput,
                userContext,
                userId,
                authorization,
                false
        );
    }

    /**
     * 执行前端传入的临时 GraphSpec。
     *
     * 临时 GraphSpec 只参与本次 DEBUG 运行，
     * 不写入工作流定义表。
     */
    @Override
    public WorkflowExecutionOutcome previewDraft(
            Long workflowId,
            WorkflowDraftPreviewRequestDTO request,
            String userId,
            String authorization) {

        String graphSpecJson =
                requirePreviewGraphSpec(request);

        WorkflowDefinition definition =
                getRequiredDefinition(workflowId);

        return execute(
                definition,
                graphSpecJson,
                safeMap(request.getInput()),
                safeMap(request.getUserContext()),
                userId,
                authorization,
                true
        );
    }

    /**
     * 执行工作流草稿并保存 DEBUG 运行记录。
     */
    private WorkflowExecutionOutcome execute(
            WorkflowDefinition definition,
            String graphSpecJson,
            Map<String, Object> rawInput,
            Map<String, Object> userContext,
            String userId,
            String authorization,
            boolean useExecutionChecksum) {

        WorkflowGraphMaterial material =
                snapshotFactory.analyzeDraft(
                        definition.getWorkflowCode(),
                        definition.getWorkflowName(),
                        graphSpecJson
                );

        if (!material.valid()) {
            throw new BusinessException(
                    400,
                    "工作流草稿校验失败："
                            + material
                            .compilationResult()
                            .errors()
            );
        }

        JsonNode inputSchema =
                snapshotFactory.readInputSchema(
                        graphSpecJson
                );

        CapabilityInputValidationResult validation =
                inputValidator.validate(
                        writeJson(inputSchema),
                        rawInput
                );

        if (!validation.isValid()) {
            throw new BusinessException(
                    400,
                    "调试参数校验失败：缺少参数="
                            + validation.getMissingParameters()
                            + "，参数错误="
                            + validation.getValidationErrors()
            );
        }

        String runId =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        /*
         * 普通调试保存完整草稿校验值。
         *
         * 快速配置预览保存排除报告定义的执行结构校验值，
         * 供下一阶段复用本次运行结果构建报告。
         */
        String runChecksum =
                useExecutionChecksum
                        ? snapshotFactory.executionChecksum(
                        material.normalizedGraphSpecJson()
                )
                        : material.checksum();

        workflowRunService.start(
                new WorkflowRunStartCommand(
                        runId,
                        null,
                        runId,
                        null,
                        useExecutionChecksum
                                ? WorkflowDebugService
                                .DRAFT_PREVIEW_REQUEST_PREFIX
                                + runId
                                : null,
                        definition.getId(),
                        definition.getWorkflowCode(),
                        definition.getWorkflowName(),
                        null,
                        null,
                        definition.getConfigRevision(),
                        runChecksum,
                        WorkflowRunOrigin.DEBUG,
                        userId,
                        validation.getSanitizedInput()
                )
        );

        long startedAt =
                System.currentTimeMillis();

        try {
            GraphExecutionRequest graphRequest =
                    GraphExecutionRequest.builder()
                            .runId(runId)
                            .userId(userId)
                            .input(
                                    validation.getSanitizedInput()
                            )
                            .userContext(userContext)
                            .authorization(authorization)
                            .secureContext(Map.of())
                            .executionPath("root")
                            .build();

            GraphExecutionResult graphResult =
                    graphExecutor.execute(
                            material
                                    .compilationResult()
                                    .compiledGraph(),
                            graphRequest
                    );

            WorkflowExecutionOutcome outcome =
                    outcomeFactory.create(
                            runId,
                            definition.getWorkflowCode(),
                            definition.getWorkflowName(),
                            null,
                            null,
                            graphResult
                    );

            workflowRunService.complete(
                    runId,
                    outcome
            );

            return outcome;

        } catch (RuntimeException exception) {
            markDebugFailedSafely(
                    runId,
                    startedAt,
                    exception
            );

            throw exception;
        }
    }

    /**
     * 查询工作流定义。
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
     * 校验临时 GraphSpec 的基础大小限制。
     */
    private String requirePreviewGraphSpec(
            WorkflowDraftPreviewRequestDTO request) {

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

        int graphSpecBytes =
                graphSpecJson
                        .getBytes(StandardCharsets.UTF_8)
                        .length;

        if (graphSpecBytes > MAX_GRAPH_SPEC_BYTES) {
            throw new BusinessException(
                    400,
                    "临时GraphSpec不能超过1MB"
            );
        }

        return graphSpecJson;
    }

    /**
     * 将空 Map 转换为空只读 Map。
     */
    private Map<String, Object> safeMap(
            Map<String, Object> source) {

        return source == null
                ? Map.of()
                : source;
    }

    /**
     * 将输入 Schema 序列化为 JSON。
     */
    private String writeJson(
            Object value) {

        try {
            return objectMapper.writeValueAsString(
                    value
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "工作流输入Schema序列化失败",
                    exception
            );
        }
    }

    /**
     * 安全保存草稿调试失败状态。
     *
     * 数据库状态更新失败时，
     * 不能覆盖真正的 Graph 异常。
     */
    private void markDebugFailedSafely(
            String runId,
            long startedAt,
            RuntimeException originalException) {

        long durationMs =
                Math.max(
                        0L,
                        System.currentTimeMillis()
                                - startedAt
                );

        try {
            workflowRunService.markFailed(
                    runId,
                    "WORKFLOW_DEBUG_FAILED",
                    "工作流草稿调试失败",
                    durationMs
            );

        } catch (RuntimeException persistenceException) {
            originalException.addSuppressed(
                    persistenceException
            );

            log.error(
                    "工作流草稿调试失败状态保存异常，runId={}",
                    runId,
                    persistenceException
            );
        }
    }
}
