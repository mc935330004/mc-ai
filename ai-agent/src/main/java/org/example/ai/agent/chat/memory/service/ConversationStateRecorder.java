package org.example.ai.agent.chat.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.workflow.plan.WorkflowPlan;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文注释：在业务查询成功后生成并保存可复用的会话上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationStateRecorder {

    private final ConversationStateService conversationStateService;

    /**
     * 中文注释：记录普通能力查询成功后的实际能力和实际调用参数。
     */
    public void recordToolResult(
            AgentRequest request,
            RoutePlan routePlan,
            String runId,
            List<ToolResult> toolResults) {
        ToolResult successfulResult = firstSuccessfulResult(toolResults);
        if (successfulResult == null) {
            return;
        }

        BusinessConversationState state = new BusinessConversationState();
        state.setRouteType(routePlan.getRouteType().name());
        state.setBusinessTopic(resolveTopic(
                routePlan.getGoal(),
                request.getUserQuestion()
        ));
        state.setCapabilityCode(successfulResult.getCapabilityCode());
        state.setLastInput(copyInput(successfulResult.getInput()));
        state.setActiveObjectIds(
                extractObjectIdentifiers(state.getLastInput())
        );
        state.setLastRunId(runId);

        saveSafely(request, state);
    }

    /**
     * 中文注释：记录查询工作流成功或部分成功后的工作流及输入参数。
     */
    public void recordWorkflowResult(
            AgentRequest request,
            WorkflowPlan plan,
            WorkflowExecutionOutcome outcome,
            String runId
    ) {
        if (outcome == null
                || (!outcome.success() && !outcome.partialSuccess())) {
            return;
        }

        BusinessConversationState state = new BusinessConversationState();
        state.setRouteType("WORKFLOW_QUERY");
        state.setBusinessTopic(resolveTopic(
                outcome.workflowName(),
                request.getUserQuestion()
        ));
        state.setWorkflowCode(outcome.workflowCode());
        state.setWorkflowVersionId(
                outcome.versionId() != null
                        ? outcome.versionId()
                        : plan.getVersionId()
        );
        state.setLastInput(copyInput(plan.getInput()));
        state.setActiveObjectIds(
                extractObjectIdentifiers(state.getLastInput())
        );
        state.setLastRunId(
                StringUtils.hasText(outcome.runId())
                        ? outcome.runId()
                        : runId
        );

        saveSafely(request, state);
    }

    /**
     * 中文注释：选择第一个成功能力，失败结果不能覆盖有效上下文。
     */
    private ToolResult firstSuccessfulResult(
            List<ToolResult> toolResults
    ) {
        if (toolResults == null) {
            return null;
        }

        return toolResults.stream()
                .filter(result ->
                        result != null && result.isSuccess()
                )
                .findFirst()
                .orElse(null);
    }

    /**
     * 中文注释：实际执行参数已在工具层脱敏，可以保存用于后续追问。
     */
    private Map<String, Object> copyInput(Object input) {
        if (!(input instanceof Map<?, ?> source)) {
            return new LinkedHashMap<>();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null) {
                result.put(String.valueOf(key), value);
            }
        });
        return result;
    }

    /**
     * 中文注释：提取项目编码、合同编号等可用于指代消解的业务标识。
     */
    private List<String> extractObjectIdentifiers(
            Map<String, Object> input
    ) {
        return input.entrySet().stream()
                .filter(entry ->
                        isIdentifierField(entry.getKey())
                                && entry.getValue() != null
                )
                .map(entry -> String.valueOf(entry.getValue()))
                .filter(StringUtils::hasText)
                .distinct()
                .limit(10)
                .toList();
    }

    /**
     * 中文注释：只识别常见业务标识字段，不保存分页参数等无关数据。
     */
    private boolean isIdentifierField(String fieldName) {
        String name = fieldName.toLowerCase();
        return name.endsWith("id")
                || name.endsWith("code")
                || name.endsWith("no")
                || name.endsWith("name");
    }

    /**
     * 中文注释：优先使用计划目标或工作流名称作为当前业务主题。
     */
    private String resolveTopic(
            String preferredTopic,
            String userQuestion
    ) {
        return StringUtils.hasText(preferredTopic)
                ? preferredTopic
                : userQuestion;
    }

    /**
     * 中文注释：状态保存失败不能破坏已经成功的业务查询响应。
     */
    private void saveSafely(
            AgentRequest request,
            BusinessConversationState state
    ) {
        try {
            conversationStateService.saveState(
                    request.getUserId(),
                    request.getConversationId(),
                    state
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "保存会话业务状态失败，conversationId={}，runId={}",
                    request.getConversationId(),
                    state.getLastRunId(),
                    exception
            );
        }
    }
    /**
     * 中文注释：记录成功的知识库问答，避免继续使用更早的业务状态。
     */
    public void recordRagResult(AgentRequest request,String runId) {
        BusinessConversationState state =
                new BusinessConversationState();

        state.setRouteType("RAG_ONLY");
        state.setBusinessTopic(request.getUserQuestion());
        state.setLastRunId(runId);

        Map<String, Object> input = new LinkedHashMap<>();
        if (request.getCategoryIds() != null) {
            input.put("categoryIds", request.getCategoryIds());
        }
        if (request.getDocumentIds() != null) {
            input.put("documentIds", request.getDocumentIds());
        }
        state.setLastInput(input);

        saveSafely(request, state);
    }
}