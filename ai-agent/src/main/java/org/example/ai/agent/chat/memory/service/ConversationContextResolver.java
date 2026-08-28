package org.example.ai.agent.chat.memory.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.chat.memory.model.ConversationRewriteDecision;
import org.example.ai.agent.chat.memory.model.ResultStatisticsContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.example.ai.agent.chat.support.ReportRequestDetector;

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
     * 明确引用上一轮查询结果的表达。
     *
     * 这里只作为模型分类失败时的确定性兜底，
     * 其他自然语言仍然交给上下文分类模型判断。
     */
    private static final List<String> RESULT_REFERENCE_MARKERS =
            List.of(
                    "上面的",
                    "上述的",
                    "刚才的",
                    "上面的数据",
                    "上面数据",
                    "上述数据",
                    "上述结果",
                    "刚才的数据",
                    "刚才的结果",
                    "上一轮数据",
                    "上一轮结果",
                    "这些数据",
                    "这些结果"
            );

    /**
     * 可以直接基于上一轮快照处理的问题表达。
     */
    private static final List<String> RESULT_OPERATION_MARKERS =
            List.of(
                    "多少",
                    "是什么",
                    "哪个",
                    "哪些",
                    "合计",
                    "总额",
                    "总和",
                    "总计",
                    "平均",
                    "最大",
                    "最小",
                    "最高",
                    "最低",
                    "统计",
                    "汇总",
                    "占比",
                    "排序",
                    "筛选",
                    "分析"
            );
    /**
     * 用户明确要求丢弃旧业务上下文时，清理旧项目和查询状态。
     */
    private static final List<String> RESET_MARKERS = List.of(
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
            "换个话题",
            "换一个项目",
            "换个项目"
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
     * 优先处理刷新和报告请求，再判断是否复用旧结果。
     */
    private String resolveDeterministicReference(AgentRequest request, BusinessConversationState state, String question) {

        boolean freshQuery = ReportRequestDetector.isExplicitRequest(question)
                || containsAny(question, REFRESH_MARKERS);

        // 要求重新查询时，不允许短追问规则提前复用旧统计结果。
        ResultStatisticsContext statistics = state.getLastStatisticsContext();
        if (!freshQuery
                && statistics != null
                && statistics.matchesArtifact(state.getResultArtifactId())
                && statistics.resolveShortOperation(question) != null) {
            applyState(request, state);
            request.setResultAnalysisRequest(true);
            return question;
        }

        if (!freshQuery
                && isExplicitResultFollowUp(question)
                && StringUtils.hasText(state.getResultArtifactId())) {
            applyState(request, state);
            request.setResultAnalysisRequest(true);
            return question;
        }

        // 先解析风险项目编号，再清理旧风险结果，避免丢失查询对象。
        if (containsAny(question, RISK_PROJECT_MARKERS)) {
            List<String> targets = safeList(state.getRiskObjectIds());
            if (targets.isEmpty()) {
                requestObjectClarification(
                        request,
                        state,
                        question,
                        "上一轮结果中没有可复用的风险项目，请明确输入项目编码。"
                );
                return null;
            }

            String rewritten = replaceMarkers(
                    question,
                    RISK_PROJECT_MARKERS,
                    "项目编码：" + formatObjectIds(targets)
            );
            return completeDeterministicResolution(
                    request, state, rewritten, targets
            );
        }

        if (containsAny(question, FIRST_PROJECT_MARKERS)) {
            List<String> objectIds = safeList(state.getDisplayObjectIds());
            if (objectIds.isEmpty()) {
                requestObjectClarification(
                        request,
                        state,
                        question,
                        "上一轮没有可复用的项目顺序，请明确输入项目编码。"
                );
                return null;
            }

            List<String> targets = List.of(objectIds.get(0));
            String rewritten = replaceMarkers(
                    question,
                    FIRST_PROJECT_MARKERS,
                    "项目编码：" + targets.get(0)
            );
            return completeDeterministicResolution(
                    request, state, rewritten, targets
            );
        }

        if (containsAny(question, CURRENT_PROJECT_MARKERS)) {
            String objectId = resolveCurrentObjectId(state);
            if (!StringUtils.hasText(objectId)) {
                requestObjectClarification(
                        request, state, question, buildProjectClarification(state)
                );
                return null;
            }

            String rewritten = replaceMarkers(
                    question,
                    CURRENT_PROJECT_MARKERS,
                    "项目编码：" + objectId
            );
            return completeDeterministicResolution(
                    request, state, rewritten, List.of(objectId)
            );
        }

        if (freshQuery) {
            return completeDeterministicResolution(
                    request, state, question, List.of()
            );
        }

        // 没有结果快照时，不能仅凭“继续分析”进入旧结果分析链路。
        if (containsAny(question, RESULT_ANALYSIS_MARKERS)
                && StringUtils.hasText(state.getResultArtifactId())) {
            applyState(request, state);
            request.setResultAnalysisRequest(true);
            return question;
        }

        return null;
    }

    /**
     * 判断问题是否明确要求处理上一轮查询结果。
     *
     * 必须同时包含“上一轮结果指代”和“取值或分析意图”，
     * 避免把“刚才那个项目的合同信息”错误识别成旧结果统计。
     */
    private boolean isExplicitResultFollowUp(String question) {
        if (!StringUtils.hasText(question)) {
            return false;
        }
        return containsAny(question, RESULT_REFERENCE_MARKERS)
                && containsAny(question, RESULT_OPERATION_MARKERS);
    }


    /**
     * 查询条件可以复用，但明确重新查询时必须让旧结果快照失效。
     */
    private String completeDeterministicResolution(AgentRequest request, BusinessConversationState state, String rewrittenQuestion, List<String> targetObjectIds) {

        boolean freshQuery = ReportRequestDetector.isExplicitRequest(rewrittenQuestion)
                        || containsAny(rewrittenQuestion, REFRESH_MARKERS);
        if (freshQuery) {
            // 保留原查询条件和项目指代，清理依赖旧结果产生的状态。
            state.setResultArtifactId(null);
            state.setLastStatisticsContext(null);
            state.setRiskEvaluationRunId(null);
            state.setRiskObjectIds(List.of());
            state.setUnknownObjectIds(List.of());
            state.setPendingReportFollowUp(null);
            state.setPendingContextQuestion(null);
            state.setAwaitingClarification(false);
            state.setUpdatedAt(LocalDateTime.now());

            // 失效状态必须保存成功，不能吞掉异常后继续使用旧快照。
            conversationStateService.saveState(
                    request.getUserId(),
                    request.getConversationId(),
                    state
            );

            applyState(request, state);
            applyObjectContext(request, state, targetObjectIds);
            request.setResultAnalysisRequest(false);
            return rewrittenQuestion;
        }

        if (containsAny(rewrittenQuestion, RESULT_ANALYSIS_MARKERS)
                && StringUtils.hasText(state.getResultArtifactId())) {
            applyState(request, state);
            applyObjectContext(request, state, targetObjectIds);
            request.setResultAnalysisRequest(true);
            return rewrittenQuestion;
        }

        // 查询其他业务时只补项目指代，不强制沿用上一轮工作流。
        applyObjectContext(request, state, targetObjectIds);
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

        request.setPreviousWorkflowCode(state.getWorkflowCode());
        request.setPreviousCapabilityCode(state.getCapabilityCode());
        Map<String, Object> lastInput = state.getLastInput();
        request.setInheritedInput(lastInput == null ? new LinkedHashMap<>() : new LinkedHashMap<>(lastInput));
        request.setResultArtifactId(state.getResultArtifactId());
        ResultStatisticsContext statistics = state.getLastStatisticsContext();
        // 不把其他快照的统计字段带入本轮请求。
        request.setLastStatisticsContext(statistics != null && statistics.matchesArtifact(state.getResultArtifactId())
                        ? statistics
                        : null);
        applyObjectContext(request, state, List.of());

        request.setLastPresentationMode(state.getLastPresentationMode());
        request.setRiskEvaluationRunId(state.getRiskEvaluationRunId());
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

    /**
     * 明确否定切换项目时，不触发项目重置。
     */
    private boolean isContextReset(String question) {
        String candidate = question.replaceAll(
                "(?:不要|不用|不需要|别|无需|暂不)(?:再)?换(?:一个|个)项目",
                ""
        );
        return RESET_MARKERS.stream().anyMatch(candidate::contains);
    }

    /**
     * 清理旧业务上下文，不删除聊天记录。
     */
    private String resetContext(AgentRequest request, String question) {
        conversationStateService.clearState(
                request.getUserId(),
                request.getConversationId()
        );

        request.setContextReset(true);
        request.setPreviousWorkflowCode(null);
        request.setPreviousCapabilityCode(null);
        request.setResultArtifactId(null);
        request.setLastStatisticsContext(null);
        request.setResultAnalysisRequest(false);
        request.setInheritedInput(new LinkedHashMap<>());
        request.setDisplayObjectIds(List.of());
        request.setRiskObjectIds(List.of());
        request.setUnknownObjectIds(List.of());
        request.setFocusedObjectId(null);
        request.setLastPresentationMode(null);
        request.setRiskEvaluationRunId(null);
        request.setContextClarificationQuestion(null);

        // 当前请求不再把旧聊天文本作为查询上下文传给模型。
        request.setConversationMemory(null);

        String remainingQuestion = removeResetMarkers(question);

        // 只说切换项目但未给新条件时，不允许继续查询旧项目。
        boolean switchingProject = question.contains("换一个项目")
                || question.contains("换个项目");

        if (switchingProject && !StringUtils.hasText(remainingQuestion)) {
            request.setContextClarificationQuestion(
                    "已清除旧项目查询上下文，请提供新的项目编码和要查询的业务信息。"
            );
        }

        return remainingQuestion;
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