package org.example.ai.agent.workflow.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.parameter.CapabilityInputSchemaValidator;
import org.example.ai.agent.capability.parameter.CapabilityInputValidationResult;
import org.example.ai.agent.common.enums.WorkflowRunOrigin;
import org.example.ai.agent.common.enums.WorkflowVersionSelection;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.runtime.GraphExecutionRequest;
import org.example.ai.agent.graph.runtime.GraphExecutionResult;
import org.example.ai.agent.graph.runtime.GraphSpecRuntimeExecutor;
import org.example.ai.agent.trace.service.RunTraceService;
import org.example.ai.agent.workflow.run.model.WorkflowRunStartCommand;
import org.example.ai.agent.workflow.run.service.WorkflowRunService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 工作流统一执行入口。
 *
 * Controller、Agent、重试服务不能直接调用 GraphSpecRuntimeExecutor。
 *
 * 必须通过本门面完成：
 * 1. 工作流版本解析；
 * 2. 版本一致性校验；
 * 3. 输入 Schema 校验；
 * 4. 用户认证上下文传递；
 * 5. 工作流运行记录管理；
 * 6. Agent Trace 绑定；
 * 7. Graph 执行；
 * 8. 执行结果持久化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowExecutionFacade {

    private final WorkflowRunService workflowRunService;

    private final WorkflowExecutionOutcomeFactory outcomeFactory;

    private final WorkflowRuntimeSnapshotResolver snapshotResolver;

    private final GraphSpecRuntimeExecutor graphExecutor;

    private final CapabilityInputSchemaValidator inputValidator;

    private final ObjectMapper objectMapper;

    private final RunTraceService runTraceService;

    /**
     * 执行正式发布的工作流。
     */
    public WorkflowExecutionOutcome execute(
            WorkflowExecutionCommand command) {

        validateCommand(command);

        /*
         * 根据版本选择策略加载工作流。
         *
         * CHAT：
         * 加载当前 ACTIVE 版本，并验证 Planner 选中的版本没有变化。
         *
         * RETRY：
         * 加载原运行使用的历史版本，允许版本状态为 RETIRED。
         */
        PublishedWorkflow workflow =
                resolveWorkflow(command);

        /*
         * 将工作流输入 Schema 转换成校验器需要的 JSON 字符串。
         */
        String inputSchemaJson =
                writeJson(workflow.inputSchema());

        /*
         * 即使 Planner 已经校验过输入，
         * Facade 仍然必须重新校验。
         *
         * 原因：
         * 1. Facade 可能被重试接口直接调用；
         * 2. 不能完全信任上游传入的 input；
         * 3. 需要统一删除 Schema 中不存在的参数。
         */
        CapabilityInputValidationResult validation =
                inputValidator.validate(
                        inputSchemaJson,
                        command.getInput()
                );

        if (!validation.isValid()) {
            throw new BusinessException(
                    400,
                    buildValidationMessage(validation)
            );
        }

        /*
         * 运行记录只能保存清洗后的输入。
         * Authorization 和 secureContext 不能写入数据库。
         */
        WorkflowRunStartCommand startCommand =
                buildStartCommand(
                        command,
                        workflow,
                        validation
                );

        long startedAt =
                System.currentTimeMillis();

        /*
         * 标记运行记录是否已经成功创建。
         *
         * 如果 start() 自身失败，数据库事务会回滚，
         * 此时不能再执行 markFailed()。
         */
        boolean runStarted = false;

        try {
            /*
             * 创建 ai_workflow_run 运行记录。
             */
            workflowRunService.start(startCommand);
            runStarted = true;

            /*
             * 只有聊天来源才绑定 ai_run_trace。
             *
             * RETRY 使用独立的工作流运行 ID，
             * 不存在对应的 Agent Trace，不能执行绑定。
             */
            bindAgentTraceIfNecessary(
                    command,
                    workflow
            );

            GraphExecutionRequest request =
                    buildGraphExecutionRequest(
                            command,
                            validation
                    );

            GraphExecutionResult graphResult =
                    graphExecutor.execute(
                            workflow.compiledGraph(),
                            request
                    );

            /*
             * Graph 的业务失败不会抛出异常。
             *
             * graphResult.success() == false 时，
             * 仍然需要生成 WorkflowExecutionOutcome，
             * 以便保存节点错误、批量项目结果等信息。
             */
            WorkflowExecutionOutcome outcome =
                    outcomeFactory.create(
                            command.getRunId(),
                            workflow.definition()
                                    .getWorkflowCode(),
                            workflow.compiledGraph()
                                    .name(),
                            workflow.version()
                                    .getId(),
                            workflow.version()
                                    .getVersionNo(),
                            graphResult
                    );

            /*
             * complete() 会根据 outcome 决定最终状态：
             *
             * success=false          -> FAILED
             * partialSuccess=true    -> PARTIAL_SUCCESS
             * 其他                   -> SUCCESS
             */
            workflowRunService.complete(
                    command.getRunId(),
                    outcome
            );

            return outcome;

        } catch (RuntimeException exception) {

            log.error(
                    "工作流执行发生异常，runId={}，workflowCode={}",
                    command.getRunId(),
                    command.getWorkflowCode(),
                    exception
            );

            /*
             * 只有运行记录已经成功创建后，
             * 才能将其更新为 FAILED。
             */
            if (runStarted) {
                markRunFailedSafely(
                        command.getRunId(),
                        startedAt,
                        exception
                );
            }

            /*
             * 必须继续抛出原始异常，
             * 由全局异常处理器统一转换响应。
             */
            throw exception;
        }
    }

    /**
     * 根据执行策略解析工作流版本。
     */
    private PublishedWorkflow resolveWorkflow(
            WorkflowExecutionCommand command) {

        PublishedWorkflow workflow;

        if (command.getVersionSelection()
                == WorkflowVersionSelection.EXACT_VERSION) {

            /*
             * 失败项目重试：
             * 固定使用原运行对应的历史版本。
             */
            workflow =
                    snapshotResolver.resolveExactVersion(
                            command.getWorkflowCode(),
                            command.getExpectedVersionId()
                    );

        } else {

            /*
             * 正常聊天执行：
             * 使用当前活动版本。
             */
            workflow =
                    snapshotResolver.resolveByCode(
                            command.getWorkflowCode()
                    );
        }

        /*
         * 无论加载活动版本还是历史版本，
         * 最终解析出的版本都必须和命令指定版本一致。
         */
        if (!Objects.equals(
                command.getExpectedVersionId(),
                workflow.version().getId()
        )) {
            throw new BusinessException(
                    409,
                    "工作流版本已经变化，请重新发起查询"
            );
        }

        return workflow;
    }

    /**
     * 创建工作流运行记录命令。
     */
    private WorkflowRunStartCommand buildStartCommand(
            WorkflowExecutionCommand command,
            PublishedWorkflow workflow,
            CapabilityInputValidationResult validation) {

        String agentRunId =
                command.getOrigin()
                        == WorkflowRunOrigin.CHAT
                        ? resolveAgentRunId(command)
                        : null;

        return new WorkflowRunStartCommand(
                command.getRunId(),
                agentRunId,
                command.getRootRunId(),
                command.getSourceRunId(),
                command.getRequestId(),
                workflow.definition().getId(),
                workflow.definition().getWorkflowCode(),
                workflow.compiledGraph().name(),
                workflow.version().getId(),
                workflow.version().getVersionNo(),
                workflow.version().getConfigRevision(),
                workflow.version().getConfigChecksum(),
                command.getOrigin(),
                command.getUserId(),
                validation.getSanitizedInput()
        );
    }

    /**
     * 仅为聊天来源绑定 Agent 主运行记录。
     */
    private void bindAgentTraceIfNecessary(
            WorkflowExecutionCommand command,
            PublishedWorkflow workflow) {

        if (command.getOrigin()
                != WorkflowRunOrigin.CHAT) {

            return;
        }

        String agentRunId =
                resolveAgentRunId(command);

        runTraceService.bindWorkflow(
                agentRunId,
                workflow.definition()
                        .getWorkflowCode(),
                workflow.version()
                        .getId()
        );
    }

    /**
     * 获取 Agent 主运行 ID。
     *
     * 兼容旧调用方式：
     * 如果没有显式传入 agentRunId，则使用当前 runId。
     */
    private String resolveAgentRunId(
            WorkflowExecutionCommand command) {

        return StringUtils.hasText(
                command.getAgentRunId()
        )
                ? command.getAgentRunId()
                : command.getRunId();
    }

    /**
     * 构造 Graph 执行请求。
     */
    private GraphExecutionRequest buildGraphExecutionRequest(
            WorkflowExecutionCommand command,
            CapabilityInputValidationResult validation) {

        return GraphExecutionRequest.builder()
                .runId(command.getRunId())
                .userId(command.getUserId())
                /*
                 * 必须使用 Schema 清洗后的输入。
                 */
                .input(
                        validation.getSanitizedInput()
                )
                .userContext(
                        command.getUserContext()
                )
                /*
                 * Authorization 只在内存中传递给业务 API。
                 * 不写入工作流运行表。
                 */
                .authorization(
                        command.getAuthorization()
                )
                .secureContext(
                        command.getSecureContext()
                )
                .executionPath("root")
                .build();
    }

    /**
     * 安全标记运行失败。
     *
     * 如果数据库状态更新再次失败，
     * 不能覆盖最初的工作流执行异常。
     */
    private void markRunFailedSafely(
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
                    "WORKFLOW_EXECUTION_EXCEPTION",
                    "工作流执行异常",
                    durationMs
            );

        } catch (RuntimeException persistenceException) {

            /*
             * 将运行状态保存异常挂到原始异常上，
             * 但最终仍然抛出 originalException。
             */
            originalException.addSuppressed(
                    persistenceException
            );

            log.error(
                    "工作流失败状态保存异常，runId={}",
                    runId,
                    persistenceException
            );
        }
    }

    /**
     * 校验工作流执行命令。
     */
    private void validateCommand(
            WorkflowExecutionCommand command) {

        if (command == null) {
            throw new BusinessException(
                    400,
                    "工作流执行命令不能为空"
            );
        }

        if (!StringUtils.hasText(
                command.getRunId()
        )) {
            throw new BusinessException(
                    400,
                    "runId不能为空"
            );
        }

        if (!StringUtils.hasText(
                command.getUserId()
        )) {
            throw new BusinessException(
                    400,
                    "userId不能为空"
            );
        }

        if (!StringUtils.hasText(
                command.getWorkflowCode()
        )) {
            throw new BusinessException(
                    400,
                    "workflowCode不能为空"
            );
        }

        if (command.getExpectedVersionId() == null) {
            throw new BusinessException(
                    400,
                    "expectedVersionId不能为空"
            );
        }

        if (command.getOrigin() == null) {
            throw new BusinessException(
                    400,
                    "工作流运行来源不能为空"
            );
        }

        if (command.getVersionSelection() == null) {
            throw new BusinessException(
                    400,
                    "工作流版本选择方式不能为空"
            );
        }

        /*
         * 用户权限全部来自当前 API 请求。
         * 不允许读取或复用历史 Token。
         */
        if (!StringUtils.hasText(
                command.getAuthorization()
        )) {
            throw new BusinessException(
                    401,
                    "用户认证信息不能为空"
            );
        }
    }

    /**
     * 将 Schema 对象序列化为 JSON。
     */
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
     * 生成输入参数校验错误信息。
     */
    private String buildValidationMessage(
            CapabilityInputValidationResult result) {

        StringBuilder builder =
                new StringBuilder(
                        "工作流输入参数校验失败。"
                );

        if (!result.getMissingParameters()
                .isEmpty()) {

            builder.append("缺少参数：")
                    .append(
                            String.join(
                                    "、",
                                    result.getMissingParameters()
                            )
                    )
                    .append("。");
        }

        if (!result.getValidationErrors()
                .isEmpty()) {

            builder.append("参数错误：")
                    .append(
                            String.join(
                                    "；",
                                    result.getValidationErrors()
                            )
                    )
                    .append("。");
        }

        return builder.toString();
    }
}