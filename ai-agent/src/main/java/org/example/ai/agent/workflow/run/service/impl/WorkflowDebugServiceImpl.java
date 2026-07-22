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

import java.util.Map;
import java.util.UUID;

/**
 * 工作流草稿调试。
 *
 * 草稿调试和正式发布执行严格分离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDebugServiceImpl implements WorkflowDebugService {

    private final WorkflowDefinitionService workflowService;
    private final WorkflowGraphSnapshotFactory snapshotFactory;
    private final CapabilityInputSchemaValidator inputValidator;
    private final GraphSpecRuntimeExecutor graphExecutor;
    private final WorkflowExecutionOutcomeFactory outcomeFactory;
    private final WorkflowRunService workflowRunService;
    private final ObjectMapper objectMapper;

    @Override
    public WorkflowExecutionOutcome debug(
            Long workflowId,
            WorkflowDebugRequestDTO request,
            String userId,
            String authorization) {

        WorkflowDefinition definition =workflowService.getById( workflowId);

        if (definition == null) {
            throw new BusinessException(
                    404,
                    "工作流不存在：" + workflowId
            );
        }

        WorkflowGraphMaterial material =snapshotFactory.analyzeDraft(
                        definition.getWorkflowCode(),
                        definition.getWorkflowName(),
                        definition.getGraphSpecJson()
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

        JsonNode inputSchema =snapshotFactory.readInputSchema(definition.getGraphSpecJson());

        Map<String, Object> rawInput = request == null
                        || request.getInput() == null
                        ? Map.of()
                        : request.getInput();

        CapabilityInputValidationResult validation = inputValidator.validate( writeJson(inputSchema), rawInput);

        if (!validation.isValid()) {
            throw new BusinessException(
                    400,
                    "调试参数校验失败：缺少参数="+ validation.getMissingParameters()
                            + "，参数错误="
                            + validation.getValidationErrors()
            );
        }

        String runId =UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        workflowRunService.start(
                new WorkflowRunStartCommand(
                        runId,
                        null,
                        runId,
                        null,
                        null,
                        definition.getId(),
                        definition.getWorkflowCode(),
                        definition.getWorkflowName(),
                        null,
                        null,
                        definition.getConfigRevision(),
                        material.checksum(),
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
                                    validation
                                            .getSanitizedInput()
                            )
                            .userContext(
                                    request == null
                                            || request
                                            .getUserContext()
                                            == null
                                            ? Map.of()
                                            : request
                                            .getUserContext()
                            )
                            .authorization(
                                    authorization
                            )
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
                            definition
                                    .getWorkflowCode(),
                            definition
                                    .getWorkflowName(),
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

    private String writeJson(Object value) {
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
     * 数据库状态更新失败时，不能覆盖真正的 Graph 异常。
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