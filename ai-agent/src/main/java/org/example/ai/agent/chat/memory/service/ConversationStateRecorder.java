package org.example.ai.agent.chat.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.workflow.answer.text.WorkflowTextFacts;
import org.example.ai.agent.workflow.plan.WorkflowPlan;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.example.ai.agent.router.IntentResult;
import org.example.ai.agent.plan.DynamicCapabilityPlan;

import java.time.LocalDateTime;
import java.util.*;

/**
 *  在业务查询成功后生成并保存可复用的会话上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationStateRecorder {

    private final ConversationStateService conversationStateService;
    /**
     * 根据报告配置准备通用待追问状态。
     */
    private final ReportFollowUpService reportFollowUpService;
    /**
     * 会话状态只保存用于指代消解的业务标识。
     */
    private static final int MAX_CONTEXT_OBJECTS = 100;
    /**
     *  记录普通能力查询成功后的实际能力和实际调用参数。
     */
    public void recordToolResult(AgentRequest request, RoutePlan routePlan, String runId, List<ToolResult> toolResults) {
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
     *  记录工作流或普通能力等待用户补参时的业务上下文。
     */
    public void recordClarification(AgentRequest request,IntentResult intentResult,String runId) {

        WorkflowPlan workflowPlan = intentResult.getWorkflowPlan();
        DynamicCapabilityPlan capabilityPlan =intentResult.getDynamicCapabilityPlan();

        BusinessConversationState state =new BusinessConversationState();

        if (workflowPlan != null && StringUtils.hasText(workflowPlan.getWorkflowCode())) {

            //  工作流补参状态保存工作流身份和部分输入。
            state.setRouteType("WORKFLOW_QUERY");
            state.setBusinessTopic(resolveTopic(
                    workflowPlan.getWorkflowName(),
                    request.getUserQuestion()
            ));
            state.setWorkflowCode(workflowPlan.getWorkflowCode());
            state.setWorkflowVersionId(workflowPlan.getVersionId());
            //  WRITE 的 input 是能力表单参数，不能作为下一轮工作流输入继承。
            state.setLastInput(
                    workflowPlan.isWriteAction()
                            ? new LinkedHashMap<>()
                            : copyInput(workflowPlan.getInput())
            );
        } else if (capabilityPlan != null && StringUtils.hasText(capabilityPlan.getCapabilityCode())) {
            //  能力补参状态保存能力身份和已经通过校验的部分输入。
            state.setRouteType("CAPABILITY_QUERY");
            state.setBusinessTopic(resolveTopic(
                    capabilityPlan.getCapabilityName(),
                    request.getUserQuestion()
            ));
            state.setCapabilityCode(capabilityPlan.getCapabilityCode());
            state.setLastInput(copyInput(capabilityPlan.getInput()));
        } else {
            //  没有明确业务身份的普通追问不能覆盖已有上下文。
            return;
        }
        state.setActiveObjectIds(extractObjectIdentifiers(state.getLastInput()));
        // 当前状态由助手补参问题产生，下一轮允许继承已选业务工作流。
        state.setAwaitingClarification(true);
        state.setLastRunId(runId);
        saveSafely(request, state);
    }

    /**
     * 记录工作流查询结果和可复用的结构化上下文。
     */
    public void recordWorkflowResult(
            AgentRequest request,
            WorkflowPlan plan,
            WorkflowExecutionOutcome outcome,
            String runId,
            String artifactId,
            WorkflowTextFacts facts,
            String presentationMode) {

        if (outcome == null
                || (!outcome.success() && !outcome.partialSuccess())) {
            return;
        }

        Map<String, Object> currentInput =
                copyInput(plan.getInput());

        BusinessConversationState previous =
                loadPreviousSafely(request);

        boolean sameScope = previous != null && !request.isContextReset() &&
                Objects.equals(previous.getWorkflowCode(), outcome.workflowCode()) &&
                Objects.equals(previous.getLastInput(), currentInput);

        List<String> inputObjectIds = extractObjectIdentifiers(currentInput);

        List<String> displayObjectIds = resolveDisplayObjectIds(facts, inputObjectIds, previous, sameScope);

        List<String> riskObjectIds =
                facts != null ? normalizeObjectIds(facts.riskObjectIds()) : sameScope
                          ? normalizeObjectIds(previous.getRiskObjectIds())
                          : List.of();

        List<String> unknownObjectIds = facts != null ? normalizeObjectIds(facts.unknownObjectIds()) : sameScope
                          ? normalizeObjectIds(previous.getUnknownObjectIds())
                          : List.of();

        BusinessConversationState state = new BusinessConversationState();

        state.setRouteType("WORKFLOW_QUERY");
        state.setBusinessTopic(resolveTopic(outcome.workflowName(), request.getUserQuestion()));
        state.setWorkflowCode(outcome.workflowCode());
        state.setWorkflowVersionId(outcome.versionId() != null ? outcome.versionId() : plan.getVersionId());
        state.setLastInput(currentInput);
        state.setActiveObjectIds(displayObjectIds);
        state.setDisplayObjectIds(displayObjectIds);
        state.setRiskObjectIds(riskObjectIds);
        state.setUnknownObjectIds(unknownObjectIds);
        state.setActiveObjectType(
                displayObjectIds.isEmpty() ? sameScope ? previous.getActiveObjectType() : null : "PROJECT");
        state.setFocusedObjectId(resolveFocusedObjectId(previous, displayObjectIds, sameScope));
        state.setLastRunId(
                StringUtils.hasText(outcome.runId())
                        ? outcome.runId()
                        : runId
        );
        state.setResultArtifactId(artifactId);
        state.setAwaitingClarification(false);
        state.setPendingContextQuestion(null);
        state.setLastPresentationMode(
                StringUtils.hasText(presentationMode)
                        ? presentationMode.trim().toUpperCase()
                        : null
        );

        if (facts != null
                && (!riskObjectIds.isEmpty()
                || !unknownObjectIds.isEmpty())) {
            state.setRiskEvaluationRunId(state.getLastRunId());
        } else if (facts == null && sameScope) {
            state.setRiskEvaluationRunId(
                    previous.getRiskEvaluationRunId()
            );
        }

        state.setPendingReportFollowUp(
                reportFollowUpService.preparePending(
                        request,
                        outcome,
                        artifactId
                ).orElse(null)
        );
        state.setUpdatedAt(LocalDateTime.now());

        saveSafely(request, state);
    }

    /**
     * 安全读取上一轮状态，读取失败不能影响当前业务结果。
     */
    private BusinessConversationState loadPreviousSafely(
            AgentRequest request) {

        try {
            return conversationStateService.loadState(
                    request.getUserId(),
                    request.getConversationId()
            ).orElse(null);
        } catch (RuntimeException exception) {
            log.warn(
                    "读取上一轮会话状态失败，conversationId={}",
                    request.getConversationId(),
                    exception
            );
            return null;
        }
    }

    /**
     * 当前结果有项目编码时使用当前结果；
     * 当前结果无法提取时才保留同一查询范围的旧顺序。
     */
    private List<String> resolveDisplayObjectIds(
            WorkflowTextFacts facts,
            List<String> inputObjectIds,
            BusinessConversationState previous,
            boolean sameScope) {

        List<String> resultObjectIds =
                facts == null
                        ? List.of()
                        : normalizeObjectIds(
                        facts.displayObjectIds()
                );

        if (!resultObjectIds.isEmpty()) {
            return resultObjectIds;
        }

        if (!inputObjectIds.isEmpty()) {
            return inputObjectIds;
        }

        if (sameScope) {
            return normalizeObjectIds(
                    previous.getDisplayObjectIds()
            );
        }

        return List.of();
    }

    /**
     * 单项目结果直接成为聚焦项目；
     * 多项目结果只保留仍然有效的旧聚焦项目。
     */
    private String resolveFocusedObjectId(BusinessConversationState previous, List<String> displayObjectIds, boolean sameScope) {
        if (displayObjectIds.size() == 1) {
            return displayObjectIds.get(0);
        }

        if (sameScope && StringUtils.hasText(previous.getFocusedObjectId()) && displayObjectIds.contains(previous.getFocusedObjectId())) {
            return previous.getFocusedObjectId();
        }

        return null;
    }

    private List<String> normalizeObjectIds(List<String> objectIds) {
        if (objectIds == null) {
            return List.of();
        }
        return objectIds.stream().filter(StringUtils::hasText).map(String::trim).distinct().limit(MAX_CONTEXT_OBJECTS).toList();
    }

    /**
     *  选择第一个成功能力，失败结果不能覆盖有效上下文。
     */
    private ToolResult firstSuccessfulResult(
            List<ToolResult> toolResults) {
        if (toolResults == null) {
            return null;
        }
        return toolResults.stream()
                .filter(result -> result != null && result.isSuccess())
                .findFirst()
                .orElse(null);
    }

    /**
     *  实际执行参数已在工具层脱敏，可以保存用于后续追问。
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
     *  提取项目编码、合同编号等可用于指代消解的业务标识。
     */
    /**
     * 提取项目编码、合同编号等业务标识，同时支持数组参数。
     */
    private List<String> extractObjectIdentifiers(Map<String, Object> input) {

        List<String> identifiers = new ArrayList<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (!isIdentifierField(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            Object rawValue = entry.getValue();
            Collection<?> values = rawValue instanceof Collection<?> collection
                            ? collection
                            : List.of(rawValue);
            for (Object value : values) {
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    identifiers.add(String.valueOf(value).trim());
                }
            }
        }
        return identifiers.stream().distinct().limit(MAX_CONTEXT_OBJECTS).toList();
    }

    /**
     *  只识别常见业务标识字段，不保存分页参数等无关数据。
     */
    private boolean isIdentifierField(String fieldName) {
        String name = fieldName.toLowerCase();
        return name.endsWith("id")
                || name.endsWith("code")
                || name.endsWith("no")
                || name.endsWith("name")
                || "projectkey".equals(name)
                || "projectkeys".equals(name);
    }

    /**
     *  优先使用计划目标或工作流名称作为当前业务主题。
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
     *  状态保存失败不能破坏已经成功的业务查询响应。
     */
    private void saveSafely(AgentRequest request, BusinessConversationState state) {
        if (state.getUpdatedAt() == null) {
            state.setUpdatedAt(LocalDateTime.now());
        }
        try {
            conversationStateService.saveState(request.getUserId(), request.getConversationId(), state);
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
     *  记录成功的知识库问答，避免继续使用更早的业务状态。
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