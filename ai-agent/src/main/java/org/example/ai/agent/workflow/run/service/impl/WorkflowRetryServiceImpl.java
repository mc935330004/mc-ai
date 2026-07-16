package org.example.ai.agent.workflow.run.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.WorkflowRunOrigin;
import org.example.ai.agent.common.enums.WorkflowVersionSelection;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.workflow.dto.WorkflowRetryFailedDTO;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.entity.WorkflowRunItem;
import org.example.ai.agent.workflow.run.service.WorkflowRetryService;
import org.example.ai.agent.workflow.run.service.WorkflowRunService;
import org.example.ai.agent.workflow.runtime.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只重试FOREACH失败项目。
 */
@Service
@RequiredArgsConstructor
public class WorkflowRetryServiceImpl
        implements WorkflowRetryService {

    private static final Pattern INPUT_ARRAY_EXPRESSION =
            Pattern.compile("^\\$input\\.([a-zA-Z][a-zA-Z0-9_-]{0,63})$");

    private final WorkflowRunService runService;
    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final WorkflowExecutionFacade executionFacade;
    private final ObjectMapper objectMapper;

    @Override
    public WorkflowExecutionOutcome retryFailed(
            String sourceRunId,
            WorkflowRetryFailedDTO request,
            String userId,
            String authorization) {

        validateRequest(request);

        /*
         * 幂等检查。
         */
        WorkflowRun existing =
                runService.findByRequestId(
                        request.getRequestId()
                );

        if (existing != null) {
            if (!Objects.equals(
                    existing.getUserId(),
                    userId
            )) {
                throw new BusinessException(
                        409,
                        "requestId已经被其他运行使用"
                );
            }

            if ("RUNNING".equals(
                    existing.getStatus()
            )) {
                throw new BusinessException(
                        409,
                        "相同重试请求正在执行"
                );
            }

            WorkflowExecutionOutcome outcome =
                    runService.readOutcome(existing);

            if (outcome != null) {
                return outcome;
            }
        }

        WorkflowRun source =
                runService.getRequiredOwned(
                        sourceRunId,
                        userId
                );

        if (!List.of(
                "FAILED",
                "PARTIAL_SUCCESS"
        ).contains(source.getStatus())) {
            throw new BusinessException(
                    400,
                    "只有失败或部分成功运行可以重试"
            );
        }

        if (source.getWorkflowVersionId() == null) {
            throw new BusinessException(
                    400,
                    "草稿调试运行不能执行历史版本重试，请重新调试草稿"
            );
        }

        List<WorkflowRunItem> failedItems =
                runService.listFailedItems(
                        sourceRunId,
                        request.getNodeId()
                );

        if (failedItems.isEmpty()) {
            throw new BusinessException(
                    400,
                    "当前节点没有可重试失败项目"
            );
        }

        if (failedItems.size() > 5) {
            throw new BusinessException(
                    400,
                    "单次最多重试5个项目"
            );
        }

        /*
         * 固定加载原版本，用于确定FOREACH输入表达式。
         */
        PublishedWorkflow workflow =
                snapshotResolver.resolveExactVersion(
                        source.getWorkflowCode(),
                        source.getWorkflowVersionId()
                );

        CompiledGraphNode node =
                workflow.compiledGraph()
                        .nodesById()
                        .get(
                                request.getNodeId()
                        );

        if (node == null
                || !(node.config()
                instanceof CompiledForEachNodeConfig config)) {

            throw new BusinessException(
                    400,
                    "指定节点不是可重试FOREACH节点"
            );
        }

        Matcher matcher =
                INPUT_ARRAY_EXPRESSION.matcher(
                        config.itemsExpression()
                );

        if (!matcher.matches()) {
            throw new BusinessException(
                    400,
                    "当前FOREACH输入不是直接的$input数组，暂不支持自动重试"
            );
        }

        String inputKey = matcher.group(1);

        Map<String, Object> retryInput =
                readMap(
                        source.getInputJson()
                );

        List<Object> retryItems =
                failedItems.stream()
                        .map(item ->
                                readObject(
                                        item.getItemJson()
                                ))
                        .toList();

        retryInput.put(
                inputKey,
                retryItems
        );

        String retryRunId =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        return executionFacade.execute(
                WorkflowExecutionCommand.builder()
                        .runId(retryRunId)
                        .userId(userId)
                        .workflowCode(
                                source.getWorkflowCode()
                        )
                        .expectedVersionId(
                                source
                                        .getWorkflowVersionId()
                        )
                        .versionSelection(
                                WorkflowVersionSelection
                                        .EXACT_VERSION
                        )
                        .origin(
                                WorkflowRunOrigin.RETRY
                        )
                        .rootRunId(
                                source.getRootRunId()
                        )
                        .sourceRunId(
                                source.getRunId()
                        )
                        .requestId(
                                request.getRequestId()
                        )
                        .input(retryInput)
                        .userContext(
                                request.getUserContext()
                        )
                        /*
                         * 使用当前请求认证信息，
                         * 绝不能复用历史Token。
                         */
                        .authorization(
                                authorization
                        )
                        .secureContext(Map.of())
                        .build()
        );
    }

    private void validateRequest(
            WorkflowRetryFailedDTO request) {

        if (request == null
                || !StringUtils.hasText(
                        request.getRequestId()
                )) {
            throw new BusinessException(
                    400,
                    "requestId不能为空"
            );
        }

        if (request.getRequestId()
                .trim()
                .length() > 64) {
            throw new BusinessException(
                    400,
                    "requestId不能超过64个字符"
            );
        }

        if (!StringUtils.hasText(
                request.getNodeId()
        )) {
            throw new BusinessException(
                    400,
                    "nodeId不能为空"
            );
        }
    }

    private Map<String, Object> readMap(
            String json) {

        try {
            return objectMapper.readValue(
                    json,
                    new TypeReference<
                            LinkedHashMap<String, Object>>() {
                    }
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "历史工作流输入解析失败",
                    exception
            );
        }
    }

    private Object readObject(String json) {
        try {
            return objectMapper.readValue(
                    json,
                    Object.class
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "失败项目输入解析失败",
                    exception
            );
        }
    }
}