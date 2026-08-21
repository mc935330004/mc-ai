package org.example.ai.agent.chat.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.chat.memory.model.ConversationRewriteDecision;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 识别当前问题与上一轮业务状态的关系。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationContextResolver {

    /**
     * 使用模型完成开放式会话关系分类。
     */
    private final ConversationContextRewriteService rewriteService;

    private final ConversationStateService  conversationStateService;

    /**
     * 只有确定性的系统控制命令使用本地规则。
     *
     * 不再通过关键词判断汇总、分析或者重新查询。
     */
    private static final List<String> RESET_MARKERS =
            List.of(
                    "不要参考之前的数据",
                    "不要参考上面的信息",
                    "忽略之前的内容",
                    "忽略上面的信息",
                    "忽略之前",
                    "忽略上面",
                    "不参考之前",
                    "不参考上面",
                    "清除上下文",
                    "清空上下文",
                    "重新开始",
                    "换个话题"
            );
    private static final List<String> CURRENT_PROJECT_MARKERS =
            List.of("这个项目", "该项目", "刚才这个项目");


    private static final List<String> FIRST_PROJECT_MARKERS =
            List.of(
                    "第一个项目",
                    "首个项目"
            );

    private static final List<String> RISK_PROJECT_MARKERS =
            List.of("这些风险项目", "上述风险项目", "刚才的风险项目");

    private static final List<String> RESULT_ANALYSIS_MARKERS = List.of("继续分析", "接着分析", "再分析一下", "为什么有风险", "为什么不规范", "为什么异常");

    private static final List<String> REPORT_MARKERS = List.of("生成完整报表", "生成报表", "完整报表");

    private static final List<String> REFRESH_MARKERS = List.of("刷新数据", "重新查询", "重新查一下", "获取最新数据");

    /**
     * 解析当前问题与上一轮业务状态的关系。
     */
    public String resolve(AgentRequest request, String runId) {

        if (request == null || !StringUtils.hasText(request.getUserQuestion())) {
            return null;
        }

        String question = request.getUserQuestion().trim();

        if (isContextReset(question)) {
            return resetContext(request, question);
        }

        BusinessConversationState state;

        try {
            state = conversationStateService.loadState(request.getUserId(), request.getConversationId()).orElse(null);
        } catch (RuntimeException exception) {
            log.warn("读取会话上下文失败，conversationId={}，runId={}", request.getConversationId(), runId, exception);
            return null;
        }
        if (state == null) {
            return null;
        }
        boolean hasPendingReportFollowUp =state.getPendingReportFollowUp() != null;
        /*
         * 明确指代优先使用后端确定性规则，
         * 不依赖上下文分类模型是否可用。
         */
        String deterministicQuestion = resolveDeterministicReference(request, state, question);
        if (StringUtils.hasText(deterministicQuestion) || StringUtils.hasText(request.getContextClarificationQuestion())) {

            /*
             * 用户已经发起明确的新上下文请求，
             * 原来的报告候选追问不应继续拦截本次请求。
             */
            if (hasPendingReportFollowUp) {
                state.setPendingReportFollowUp(null);
                state.setUpdatedAt(LocalDateTime.now());
                saveStateSafely(request, state);
            }
            return deterministicQuestion;
        }

        /*
         * 没有识别出明确的新上下文请求时，
         * 继续交给原有报告追问流程处理。
         */
        if (hasPendingReportFollowUp) {
            return null;
        }
        String clarificationQuestion = resolveClarificationFallback(request, state, question);
        if (StringUtils.hasText(clarificationQuestion)) {
            return clarificationQuestion;
        }
        ConversationRewriteDecision decision = rewriteService.decide(request, state, runId).orElse(null);
        /*
         * 模型返回空时，明确指代已经由上面的确定性规则处理。
         * 剩余问题没有足够依据继承旧状态，按新话题处理。
         */
        if (decision == null) {
            return null;
        }

        if (rewriteService.isResultAnalysis(decision)) {
            request.setResultAnalysisRequest(true);
            applyState(request, state);

            return StringUtils.hasText(
                    decision.rewrittenQuestion()
            )
                    ? decision.rewrittenQuestion().trim()
                    : question;
        }

        if (rewriteService.isFollowUpQuery(decision)) {
            applyState(request, state);
            return decision.rewrittenQuestion().trim();
        }

        return null;
    }
    /**
     * 处理可以唯一确定的项目指代。
     */
    private String resolveDeterministicReference(
            AgentRequest request,
            BusinessConversationState state,
            String question) {

        if (containsAny(question, RISK_PROJECT_MARKERS)) {
            List<String> riskObjectIds =
                    safeList(state.getRiskObjectIds());

            if (riskObjectIds.isEmpty()) {
                requestObjectClarification(
                        request,
                        state,
                        question,
                        "上一轮结果中没有可复用的风险项目，"
                                + "请明确输入需要查询的项目编码。"
                );
                return null;
            }

            String rewritten = replaceMarkers(
                    question,
                    RISK_PROJECT_MARKERS,
                    "项目编码：" + formatObjectIds(riskObjectIds)
            );

            return completeDeterministicResolution(
                    request,
                    state,
                    rewritten,
                    riskObjectIds
            );
        }

        if (containsAny(question, FIRST_PROJECT_MARKERS)) {
            List<String> displayObjectIds =
                    safeList(state.getDisplayObjectIds());

            if (displayObjectIds.isEmpty()) {
                requestObjectClarification(
                        request,
                        state,
                        question,
                        "上一轮没有可复用的项目顺序，"
                                + "请明确输入项目编码。"
                );
                return null;
            }

            List<String> target =
                    List.of(displayObjectIds.get(0));

            String rewritten = replaceMarkers(
                    question,
                    FIRST_PROJECT_MARKERS,
                    "项目编码：" + target.get(0)
            );

            return completeDeterministicResolution(
                    request,
                    state,
                    rewritten,
                    target
            );
        }

        if (containsAny(question, CURRENT_PROJECT_MARKERS)) {
            String focusedObjectId =
                    resolveCurrentObjectId(state);

            if (!StringUtils.hasText(focusedObjectId)) {
                requestObjectClarification(
                        request,
                        state,
                        question,
                        buildProjectClarification(state)
                );
                return null;
            }

            List<String> target =
                    List.of(focusedObjectId);

            String rewritten = replaceMarkers(
                    question,
                    CURRENT_PROJECT_MARKERS,
                    "项目编码：" + focusedObjectId
            );

            return completeDeterministicResolution(
                    request,
                    state,
                    rewritten,
                    target
            );
        }

        if (containsAny(
                question,
                RESULT_ANALYSIS_MARKERS
        )) {
            applyState(request, state);
            request.setResultAnalysisRequest(true);
            return question;
        }

        if (containsAny(question, REPORT_MARKERS)
                || containsAny(question, REFRESH_MARKERS)) {
            /*
             * 完整报表和刷新属于重新执行上一轮查询，
             * 不能错误进入上一轮Artifact分析链路。
             */
            applyState(request, state);
            request.setResultAnalysisRequest(false);
            return question;
        }

        return null;
    }

    /**
     * 根据问题类型决定复用Artifact还是重新执行查询。
     */
    private String completeDeterministicResolution(
            AgentRequest request,
            BusinessConversationState state,
            String rewrittenQuestion,
            List<String> targetObjectIds) {

        if (containsAny(
                rewrittenQuestion,
                RESULT_ANALYSIS_MARKERS
        )) {
            applyState(request, state);
            applyObjectContext(
                    request,
                    state,
                    targetObjectIds
            );
            request.setResultAnalysisRequest(true);
            return rewrittenQuestion;
        }

        if (containsAny(rewrittenQuestion, REPORT_MARKERS)
                || containsAny(
                rewrittenQuestion,
                REFRESH_MARKERS
        )) {
            applyState(request, state);
            applyObjectContext(
                    request,
                    state,
                    targetObjectIds
            );
            request.setResultAnalysisRequest(false);
            return rewrittenQuestion;
        }

        /*
         * “这个项目的合同”等新业务问题只补项目编码，
         * 不强制复用上一轮工作流，避免路由到错误工作流。
         */
        applyObjectContext(
                request,
                state,
                targetObjectIds
        );
        return rewrittenQuestion;
    }

    private String resolveCurrentObjectId(
            BusinessConversationState state) {

        if (StringUtils.hasText(
                state.getFocusedObjectId()
        )) {
            return state.getFocusedObjectId().trim();
        }

        List<String> displayObjectIds =
                safeList(state.getDisplayObjectIds());

        return displayObjectIds.size() == 1
                ? displayObjectIds.get(0)
                : null;
    }

    /**
     * 多项目指代不明确时保存原问题并生成追问。
     */
    private void requestObjectClarification(
            AgentRequest request,
            BusinessConversationState state,
            String originalQuestion,
            String clarificationQuestion) {

        request.setContextClarificationQuestion(
                clarificationQuestion
        );

        state.setAwaitingClarification(true);
        state.setPendingContextQuestion(originalQuestion);
        state.setUpdatedAt(LocalDateTime.now());

        saveStateSafely(request, state);
    }

    private String buildProjectClarification(
            BusinessConversationState state) {

        List<String> objectIds =
                safeList(state.getDisplayObjectIds());

        if (objectIds.isEmpty()) {
            return "上一轮没有可复用的项目编码，"
                    + "请明确输入需要查询的项目编码。";
        }

        return "上一轮包含多个项目，请明确项目编码。"
                + "可选项目："
                + formatObjectIds(
                objectIds.stream()
                        .limit(5)
                        .toList()
        );
    }

    private String formatObjectIds(List<String> objectIds) {

        return String.join("、", objectIds.stream().limit(20).toList());
    }

    private boolean containsAny(String question, List<String> markers) {

        return markers.stream().anyMatch(
                question::contains
        );
    }

    private String replaceMarkers(String question, List<String> markers, String replacement) {
        String result = question;
        for (String marker : markers) {
            result = result.replace(marker, replacement);
        }

        return result;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
    }

    private void saveStateSafely(AgentRequest request, BusinessConversationState state) {
        try {
            conversationStateService.saveState(
                    request.getUserId(),
                    request.getConversationId(),
                    state
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "保存上下文澄清状态失败，conversationId={}",
                    request.getConversationId(),
                    exception
            );
        }
    }

    /**
     * 处理用户对项目指代追问或工作流补参的回答。
     */
    private String resolveClarificationFallback(AgentRequest request, BusinessConversationState state, String question) {

        if (!state.isAwaitingClarification()
                || !isSimpleBusinessIdentifier(question)) {
            return null;
        }

        String objectId = question.trim();
        String pendingQuestion = state.getPendingContextQuestion();

        state.setFocusedObjectId(objectId);
        state.setAwaitingClarification(false);
        state.setPendingContextQuestion(null);
        state.setUpdatedAt(LocalDateTime.now());
        saveStateSafely(request, state);
        if (StringUtils.hasText(pendingQuestion)) {
            String rewritten = pendingQuestion;

            rewritten = replaceMarkers(rewritten, CURRENT_PROJECT_MARKERS, "项目编码：" + objectId);
            rewritten = replaceMarkers(rewritten, FIRST_PROJECT_MARKERS, "项目编码：" + objectId);
            rewritten = replaceMarkers(rewritten, RISK_PROJECT_MARKERS, "项目编码：" + objectId);
            return completeDeterministicResolution(request, state, rewritten, List.of(objectId));
        }

        /*
         * 保留原有工作流缺少参数时的补参兼容。
         */
        applyState(request, state);
        request.setFocusedObjectId(objectId);

        String topic = StringUtils.hasText(state.getBusinessTopic())
                        ? state.getBusinessTopic().trim()
                        : "上一轮业务查询";

        return topic + "，补充查询条件：" + objectId;
    }

    /**
     * 项目编号只允许常用字母、数字、下划线和连接符。
     */
    private boolean isSimpleBusinessIdentifier(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        return question.trim().matches("[A-Za-z0-9_-]{2,64}");
    }
    /**
     * 将服务端可信会话状态写入当前请求。
     */
    /**
     * 将服务端可信状态写入当前请求。
     */
    private void applyState(AgentRequest request, BusinessConversationState state) {

        request.setPreviousWorkflowCode(
                state.getWorkflowCode()
        );
        request.setPreviousCapabilityCode(
                state.getCapabilityCode()
        );

        Map<String, Object> lastInput =
                state.getLastInput();

        request.setInheritedInput(
                lastInput == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(lastInput)
        );

        request.setResultArtifactId(
                state.getResultArtifactId()
        );

        applyObjectContext(
                request,
                state,
                List.of()
        );

        request.setLastPresentationMode(
                state.getLastPresentationMode()
        );
        request.setRiskEvaluationRunId(
                state.getRiskEvaluationRunId()
        );
    }

    private void applyObjectContext(AgentRequest request, BusinessConversationState state, List<String> targetObjectIds) {

        request.setDisplayObjectIds(
                safeList(state.getDisplayObjectIds())
        );
        request.setRiskObjectIds(
                safeList(state.getRiskObjectIds())
        );
        request.setUnknownObjectIds(
                safeList(state.getUnknownObjectIds())
        );

        if (targetObjectIds != null && targetObjectIds.size() == 1) {
            request.setFocusedObjectId(targetObjectIds.get(0));
        } else {
            request.setFocusedObjectId(state.getFocusedObjectId());
        }
    }

    private boolean isContextReset(String question) {
        return RESET_MARKERS.stream().anyMatch(question::contains);
    }

    /**
     * 清理当前会话业务状态。
     */
    private String resetContext(
            AgentRequest request,
            String question) {

        conversationStateService.clearState(
                request.getUserId(),
                request.getConversationId()
        );

        request.setContextReset(true);
        request.setPreviousWorkflowCode(null);
        request.setPreviousCapabilityCode(null);
        request.setResultArtifactId(null);
        request.setResultAnalysisRequest(false);
        request.setInheritedInput( new LinkedHashMap<>());
        request.setDisplayObjectIds(List.of());
        request.setRiskObjectIds(List.of());
        request.setUnknownObjectIds(List.of());
        request.setFocusedObjectId(null);
        request.setLastPresentationMode(null);
        request.setRiskEvaluationRunId(null);
        request.setContextClarificationQuestion(null);
        return removeResetMarkers(question);
    }

    /**
     * 支持“换个话题，查询其他项目”清理后继续执行。
     */
    private String removeResetMarkers(
            String question) {
        String result = question;
        for (String marker :RESET_MARKERS) {
            result = result.replace(
                    marker,
                    ""
            );
        }
        return result.replaceFirst(
                        "^[\\s，,。；;：:]+",
                        "") .trim();
    }
}