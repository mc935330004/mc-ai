package org.example.ai.agent.chat.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.entity.AgentStreamEvent;
import org.example.ai.agent.chat.memory.model.ReportFollowUpDecision;
import org.example.ai.agent.chat.memory.service.ReportFollowUpService;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.example.ai.agent.chat.support.ActiveAgentRunRegistry;
import org.example.ai.agent.common.enums.ReportQueryType;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.vo.ActionFormVO;
import org.example.ai.agent.chat.support.AgentClientDisconnectedException;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.chat.support.AgentStreamSessionFactory;
import org.example.ai.agent.common.enums.AgentStreamEventType;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryRequest;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryResponse;
import org.example.ai.agent.modules.knowledgebase.service.impl.KnowledgeDocumentQueryService;
import org.example.ai.agent.pending.entity.PendingAction;
import org.example.ai.agent.pending.service.PendingActionService;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.example.ai.agent.plan.PlanTemplateRegistry;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.router.IntentResult;
import org.example.ai.agent.router.IntentRouter;
import org.example.ai.agent.router.RouteType;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolExecutor;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.trace.service.RunTraceService;
import org.example.ai.agent.vo.ActionPreviewVO;
import org.example.ai.agent.workflow.answer.WorkflowAnswerAnalysisResult;
import org.example.ai.agent.workflow.answer.WorkflowAnswerComposeResult;
import org.example.ai.agent.workflow.answer.WorkflowAnswerComposer;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPreparation;
import org.example.ai.agent.workflow.answer.analysis.WorkflowAnswerAnalysisDecider;
import org.example.ai.agent.workflow.answer.analysis.WorkflowAnswerAnalysisProperties;
import org.example.ai.agent.workflow.answer.report.ReportBlockMapper;
import org.example.ai.agent.workflow.plan.WorkflowPlan;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionCommand;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionFacade;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.memory.service.ConversationStateRecorder;
import org.example.ai.agent.chat.memory.service.ConversationContextResolver;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactAnalysisResult;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactAnalysisService;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.workflow.answer.report.ReportSchemaBuilder;
import org.example.ai.agent.observability.AgentMetrics;
import org.example.ai.agent.workflow.answer.analysis.ReportAnalysisFallbackService;
import org.example.ai.agent.workflow.answer.analysis.ReportAnalysisInput;
import org.example.ai.agent.workflow.answer.analysis.ReportAnalysisInputBuilder;
import org.example.ai.agent.workflow.answer.presentation.WorkflowAnswerPolicyResolver;
import org.example.ai.agent.workflow.answer.text.WorkflowTextFactBuilder;
import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.protocol.response.ResponseReference;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.chat.protocol.response.ReportSchema;
import org.example.ai.agent.chat.protocol.response.ResponseDocument;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import org.example.ai.agent.answer.text.BusinessTextAnswerService;
import org.example.ai.agent.answer.text.BusinessTextFacts;
import org.example.ai.agent.answer.text.CapabilityAnswerFactBuilder;
import org.example.ai.agent.answer.text.SafeModelInputBuilder;
import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.block.StatusBlock;
import org.example.ai.agent.chat.protocol.response.ReportSection;
import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.business.BusinessAssistantService;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@Service
public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private final AgentStreamSessionFactory streamSessionFactory;
    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService;
    private final Executor agentChatExecutor;
    private final WorkflowExecutionFacade workflowExecutionFacade;
    private final ModelUsageService modelUsageService;
    private final ResultArtifactAnalysisService resultArtifactAnalysisService;
    private final WorkflowAnswerComposer workflowAnswerComposer;
    private final WorkflowAnswerPolicyResolver workflowAnswerPolicyResolver;
    private final WorkflowTextFactBuilder workflowTextFactBuilder;
    private final ActiveAgentRunRegistry activeAgentRunRegistry;
    private final IntentRouter intentRouter;
    private final PlanTemplateRegistry planTemplateRegistry;
    private final ToolExecutor toolExecutor;
    private final RunTraceService runTraceService;
    private final PendingActionService pendingActionService;
    private final ObjectMapper objectMapper;
    private final AiChatSessionService aiChatSessionService;
    private final ConversationStateRecorder conversationStateRecorder;
    private final ConversationContextResolver conversationContextResolver;
    private final ReportSchemaBuilder reportSchemaBuilder;
    private final WorkflowAnswerAnalysisDecider workflowAnswerAnalysisDecider;
    private final WorkflowAnswerAnalysisProperties analysisProperties;
    private final ExecutorService workflowAnswerAnalysisExecutor;
    private final ReportFollowUpService reportFollowUpService;
    private final ReportAnalysisInputBuilder reportAnalysisInputBuilder;
    private final ReportAnalysisFallbackService reportAnalysisFallbackService;
    private final AgentMetrics agentMetrics;
    private final ReportBlockMapper reportBlockMapper;
    private final BusinessTextAnswerService businessTextAnswerService;
    private final CapabilityAnswerFactBuilder capabilityAnswerFactBuilder;
    private final SafeModelInputBuilder safeModelInputBuilder;
    private final BusinessAssistantService businessAssistantService;
    /**
     * 使用显式构造器注入命名线程池。
     *
     * Executor 类型可能存在多个 Bean，必须使用 Qualifier 指定
     * agentChatExecutor，避免 Lombok 未将字段注解复制到构造器参数。
     */
    public DefaultAgentOrchestrator(
            AgentStreamSessionFactory streamSessionFactory,
            KnowledgeDocumentQueryService knowledgeDocumentQueryService,
            @Qualifier("agentChatExecutor") Executor agentChatExecutor,
            IntentRouter intentRouter,
            PlanTemplateRegistry planTemplateRegistry,
            ToolExecutor toolExecutor,
            RunTraceService runTraceService,
            PendingActionService pendingActionService,
            ObjectMapper objectMapper,
            WorkflowExecutionFacade workflowExecutionFacade,
            WorkflowAnswerComposer workflowAnswerComposer,
            ConversationStateRecorder conversationStateRecorder,
            ConversationContextResolver conversationContextResolver,
            //  注入会话服务，统一保存助手回答。
            AiChatSessionService aiChatSessionService,
            ResultArtifactAnalysisService resultArtifactAnalysisService,
            ReportFollowUpService reportFollowUpService,
            ReportSchemaBuilder reportSchemaBuilder,
            ModelUsageService modelUsageService,
            WorkflowAnswerAnalysisDecider workflowAnswerAnalysisDecider,
            WorkflowAnswerAnalysisProperties analysisProperties,
            ReportAnalysisInputBuilder reportAnalysisInputBuilder,
            ReportAnalysisFallbackService reportAnalysisFallbackService,
            AgentMetrics agentMetrics,
            WorkflowAnswerPolicyResolver workflowAnswerPolicyResolver,
            WorkflowTextFactBuilder workflowTextFactBuilder,
            ActiveAgentRunRegistry activeAgentRunRegistry,
            ReportBlockMapper reportBlockMapper,
            BusinessTextAnswerService businessTextAnswerService,
            CapabilityAnswerFactBuilder capabilityAnswerFactBuilder,
            SafeModelInputBuilder safeModelInputBuilder,
            BusinessAssistantService businessAssistantService,

            @Qualifier("workflowAnswerAnalysisExecutor") ExecutorService workflowAnswerAnalysisExecutor) {
        this.streamSessionFactory = streamSessionFactory;
        this.knowledgeDocumentQueryService = knowledgeDocumentQueryService;
        this.agentChatExecutor = agentChatExecutor;
        this.intentRouter = intentRouter;
        this.planTemplateRegistry = planTemplateRegistry;
        this.toolExecutor = toolExecutor;
        this.runTraceService = runTraceService;
        this.pendingActionService = pendingActionService;
        this.objectMapper = objectMapper;
        this.workflowExecutionFacade = workflowExecutionFacade;
        this.workflowAnswerComposer = workflowAnswerComposer;
        this.conversationStateRecorder = conversationStateRecorder;
        this.conversationContextResolver = conversationContextResolver;
        this.aiChatSessionService = aiChatSessionService;
        this.modelUsageService = modelUsageService;
        this.resultArtifactAnalysisService = resultArtifactAnalysisService;
        this.reportFollowUpService = reportFollowUpService;
        this.reportSchemaBuilder = reportSchemaBuilder;
        this.workflowAnswerAnalysisDecider = workflowAnswerAnalysisDecider;
        this.analysisProperties = analysisProperties;
        this.workflowAnswerAnalysisExecutor = workflowAnswerAnalysisExecutor;
        this.reportAnalysisInputBuilder = reportAnalysisInputBuilder;
        this.reportAnalysisFallbackService = reportAnalysisFallbackService;
        this.agentMetrics = agentMetrics;
        this.workflowAnswerPolicyResolver = workflowAnswerPolicyResolver;
        this.workflowTextFactBuilder = workflowTextFactBuilder;
        this.activeAgentRunRegistry = activeAgentRunRegistry;
        this.reportBlockMapper = reportBlockMapper;
        this.businessTextAnswerService = businessTextAnswerService;
        this.capabilityAnswerFactBuilder = capabilityAnswerFactBuilder;
        this.safeModelInputBuilder = safeModelInputBuilder;
        this.businessAssistantService = businessAssistantService;

    }
    /**
     * 提交聊天任务，任务取消由会话管理，避免排队任务跳过取消收尾。
     */
    @Override
    public SseEmitter chat(AgentRequest request) {
        String runId = UUID.randomUUID().toString().replace("-", "");
        AgentStreamSession stream = streamSessionFactory.create(
                runId,
                request.getConversationId()
        );
        activeAgentRunRegistry.register(
                runId,
                request.getUserId(),
                request.getConversationId(),
                stream
        );
        try {
            agentChatExecutor.execute(() -> doChat(request, stream, runId));
        } catch (RuntimeException exception) {
            activeAgentRunRegistry.remove(runId);
            stream.error(exception);
        }
        return stream.getEmitter();
    }

    @Override
    public boolean cancel(String userId, String conversationId, String runId) {
        return activeAgentRunRegistry.cancel(runId, userId, conversationId);
    }

    /**
     * 真正执行 Agent 聊天逻辑。
     *
     * 拆成单独方法是为了让 chat() 方法更清晰。
     */
    private void doChat(AgentRequest request, AgentStreamSession stream, String runId) {
        long startTime = System.currentTimeMillis();
        try {
            //校验请求参数。
            validateRequest(request);
            // 创建运行主记录。
            runTraceService.startRun(runId, request);
            // 排队期间已取消时，从这里进入统一取消收尾。
            stream.bindExecutionThread();
            // 推送开始处理事件。
            stream.send("thinking",
                    AgentStreamEvent.of(
                            runId,
                            AgentStreamEventType.THINKING.name(),
                            "正在处理。",
                            null
                    )
            );
            //  runId 用于记录上下文改写模型的调用链路。
            String contextualQuestion =conversationContextResolver.resolve(request,runId );
            stream.checkCancellation();
            request.setContextualQuestion(contextualQuestion);
            /*
             * 多项目指代无法唯一确定时直接追问，
             * 禁止把“这个项目”重新交给普通工作流路由。
             */
            if (StringUtils.hasText(request.getContextClarificationQuestion())) {
                publishAssistantAnswer(request, stream, runId, request.getContextClarificationQuestion());
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                return;
            }
            /*
             *  纯“清除上下文”命令不需要进入工作流、
             * 能力模块或 RAG，直接返回确定性结果。
             */
            if (request.isContextReset()&& !StringUtils.hasText(contextualQuestion)) {
                publishAssistantAnswer(request, stream, runId, "当前会话上下文已清除。");
                runTraceService.markSuccess(runId,System.currentTimeMillis() - startTime);
                return;
            }
            /*
             * 报告业务追问在结果分析和普通意图路由之前处理。
             *
             * 科目候选和目标参数已经由服务端确定，
             * 不能再次调用模型选择能力。
             */
            ReportFollowUpDecision followUpDecision =reportFollowUpService.resolve(request);

            if (followUpDecision.status()!= ReportFollowUpDecision.Status.NONE) {
                handleReportFollowUp(request, stream, runId, followUpDecision);
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                return;
            }
            /*
             * 结果追问必须在普通工作流和能力路由之前处理。
             */
            if (request.isResultAnalysisRequest()) {
                runTraceService.updateRouteType(runId,RouteType.RESULT_ANALYSIS );
                /*
                 * 模型识别为结果分析，
                 * 但当前会话没有可复用快照时，
                 * 直接给出下一步操作指引。
                 *
                 * 禁止继续匹配其他工作流或者能力接口。
                 */
                if (!StringUtils.hasText(request.getResultArtifactId())) {
                    publishAssistantAnswer(request, stream, runId,
                            "当前会话没有可复用的查询结果。"
                                    + "请先查询需要分析的业务数据，"
                                    + "然后再进行汇总、统计、筛选或对比。");

                    runTraceService.markSuccess(runId, System.currentTimeMillis()- startTime);
                    return;
                }
                stream.send("thinking",
                        AgentStreamEvent.of(runId, AgentStreamEventType.THINKING.name(), "正在分析上一轮查询结果。", null)
                );
                executeResultAnalysis(request, stream, runId);
                stream.checkCancellation();
                runTraceService.markSuccess(runId,System.currentTimeMillis()- startTime);
                return;
            }
            IntentResult intentResult =intentRouter.route(request, runId);
            stream.checkCancellation();
            //  更新路由类型。
            runTraceService.updateRouteType(runId, intentResult.getRouteType());
            // 推送路由结果，方便前端展示和后端排查。
            stream.send("thinking", AgentStreamEvent.of(runId, AgentStreamEventType.THINKING.name(),
                    "路由结果：" + intentResult.getRouteType() + "，原因：" + intentResult.getReason(), intentResult));

            /*
             * 已确认的只读业务查询交给新的单一业务编排入口。
             * RAG、混合检索、写操作和已有工作流查询继续保持原执行边界。
             */
            if (isConfirmedBusinessRead(intentResult)) {
                businessAssistantService.handle(request, stream, runId);
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                return;
            }

            /*
             *  根据路由结果生成运行计划。
             *
             * RoutePlan 只描述“准备做哪些步骤”，不负责真正执行。
             * 当前阶段可以先把计划返回给前端，方便你确认规划是否合理。
             */
            RoutePlan routePlan = planTemplateRegistry.buildPlan(runId, request, intentResult);

            //  推送运行计划。
            stream.send(
                    "plan",
                    AgentStreamEvent.of(
                            runId,
                            AgentStreamEventType.PLAN.name(),
                            "已生成 Agent 运行计划。",
                            routePlan
                    )
            );
            /*
             *  如果信息不足，需要追问用户。
             *
             * 例如用户问：
             * “查一下那个项目”
             *
             * 这类问题没有明确项目名称，也没有明确查询目标，
             * 不应该继续调用 RAG 或业务接口。
             */
            if (intentResult.isNeedClarify()) {
                //  先保存已选工作流和部分参数，下一轮补充内容才能续接执行。
                conversationStateRecorder.recordClarification(
                        request,
                        intentResult,
                        runId
                );
                WorkflowPlan workflowPlan = intentResult.getWorkflowPlan();
                /*
                 * WRITE参数不足时发送结构化表单，
                 * 不能只返回一段纯文本。
                 */
                if (workflowPlan != null && workflowPlan.isWriteAction() && workflowPlan.getActionInputSchema() != null) {
                    sendActionForm(
                            request,
                            stream,
                            runId,
                            workflowPlan
                    );

                    runTraceService.markSuccess( runId,System.currentTimeMillis()- startTime);
                    stream.complete();
                    return;
                }
                //  保存需要用户补充信息的追问。
                publishAssistantAnswer(
                        request,
                        stream,
                        runId,
                        intentResult.getClarifyQuestion()
                );

                runTraceService.markSuccess(
                        runId,
                        System.currentTimeMillis()
                                - startTime
                );
                return;
            }
            /*
             * 如果是危险操作，直接拒绝。
             *
             * 例如：
             * - 删除全部合同
             * - 清空项目数据
             * - 批量作废所有审批
             *
             * 第一版 Agent 必须拒绝这类操作。
             */
            if (intentResult.getRouteType() == RouteType.REJECT) {
                // 拒绝回答不再携带内部 RoutePlan，并复用统一回答协议。
                //  保存风险操作拒绝回答。
                publishAssistantAnswer(
                        request,
                        stream,
                        runId,
                        "该操作存在风险，当前版本不支持由 Agent 自动执行。"
                );
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                return;
            }

            if (intentResult.getRouteType() == RouteType.WORKFLOW_QUERY) {

                WorkflowExecutionOutcome outcome = executeWorkflowQuery(request, stream, runId, intentResult );
                stream.checkCancellation();
                long duration =System.currentTimeMillis() - startTime;

                if (outcome.success()) {
                    /*
                     * 部分成功仍属于一次有效业务查询，
                     * 具体失败项目已经写入批量摘要。
                     */
                    runTraceService.markSuccess(runId, duration);
                } else {
                    runTraceService.markFailed(runId, duration, outcome.errorMessage());
                }
                return;
            }

            // 写操作只发送预览，当前阶段绝不进入 ToolExecutor
            if (intentResult.getRouteType() == RouteType.WORKFLOW_ACTION) {
                PendingAction pendingAction =pendingActionService.createPendingAction( runId,request.getUserId(),
                        intentResult.getDynamicCapabilityPlan());
                sendActionPreview(
                        request,
                        stream,
                        runId,
                        intentResult.getDynamicCapabilityPlan(),
                        pendingAction
                );
                runTraceService.markSuccess(runId,System.currentTimeMillis() - startTime);
                stream.complete();
                return;
            }
            /*
             * RAG_ONLY 走企业知识库问答。
             * 其它业务查询类型交给 ToolExecutor 执行真实业务能力。
             */
            if (intentResult.getRouteType() == RouteType.RAG_ONLY) {
                executeRagOnly(request, stream, runId, routePlan);
                stream.checkCancellation();
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                return;
            }
            // 防御性校验：即使前面的路由发生错误，WRITE 能力也不能直接进入工具执行器
            DynamicCapabilityPlan selectedPlan = intentResult.getDynamicCapabilityPlan();
            if (selectedPlan != null && "WRITE".equalsIgnoreCase(selectedPlan.getSideEffect())) {
                PendingAction pendingAction =pendingActionService.createPendingAction( runId,request.getUserId(),
                        selectedPlan);
                sendActionPreview(
                        request,
                        stream,
                        runId,
                        selectedPlan,
                        pendingAction
                );
                runTraceService.markSuccess(runId,System.currentTimeMillis() - startTime);
                stream.complete();
                return;
            }
            // BUSINESS_QUERY / MIXED_QUERY / STATISTIC_QUERY 走工具执行链路。
            executeToolPlan(request, stream, runId, routePlan);
            stream.checkCancellation();
            runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);

        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - startTime;
            // 同时检查用户取消标志，避免底层组件清除线程中断后漏判。
            if (stream.isCancellationRequested() || isRunCancelled(exception)) {
                // 允许当前线程完成取消结果的数据库收尾。
                Thread.interrupted();
                handleInterruptedRun(request, stream, runId, duration, true, exception);
                return;
            }
            AgentClientDisconnectedException disconnected =findClientDisconnected(exception);
            if (disconnected != null) {
                // 异常已经向外传播时，保存已有回答，避免快照一直停留在RUNNING。
                log.debug("客户端连接已断开，保存当前回答，runId={}", runId);
                handleInterruptedRun(request, stream, runId, duration, false, disconnected );
                return;
            }
            handleInterruptedRun(request, stream, runId, duration, false, exception);
        } finally {
            // 清理线程中断状态，再移除任务登记。
            stream.unbindExecutionThread();
            activeAgentRunRegistry.remove(runId);
        }
    }

    /**
     * 只有已匹配的注册 READ 能力才能进入新业务助手，畸形写计划和工作流计划继续走旧防线。
     */
    static boolean isConfirmedBusinessRead(IntentResult result) {
        if (result == null || result.isNeedClarify() || result.getWorkflowPlan() != null) {
            return false;
        }
        DynamicCapabilityPlan capability = result.getDynamicCapabilityPlan();
        return capability != null
                && capability.isMatched()
                && "READ".equalsIgnoreCase(capability.getSideEffect())
                && (result.getRouteType() == RouteType.BUSINESS_QUERY
                || result.getRouteType() == RouteType.STATISTIC_QUERY);
    }


    /**
     * 统一处理取消和普通异常。
     * 先保存完整快照，再通知前端结束。
     */
    private void handleInterruptedRun(AgentRequest request, AgentStreamSession stream, String runId, long duration, boolean cancelled, Throwable failure) {
        // 异常和取消可能同时发生，以已经接受的用户取消为准。
        cancelled = stream.beginInterruptedFinalization() || cancelled;
        if (cancelled) {
            Thread.interrupted();
        }
        try {
            if (cancelled) {
                runTraceService.markCancelled(runId, duration, "用户主动终止");
            } else {
                runTraceService.markFailed(
                        runId, duration, failure.getMessage()
                );
            }
        } catch (RuntimeException traceException) {
            // 运行日志写入失败不能阻止当前连接收尾。
            log.warn("记录运行终态失败，runId={}，errorType={}",
                    runId, traceException.getClass().getSimpleName());
        }
        if (stream.isCompleted()) {
            return;
        }
        ResponseDocument document;
        try {
            document = prepareInterruptedResponse(stream, cancelled);
        } catch (RuntimeException prepareException) {
            log.error("准备最终快照失败，runId={}，errorType={}",
                    runId, prepareException.getClass().getSimpleName());
            stream.error(prepareException);
            return;
        }
        StringBuilder content = new StringBuilder(cancelled ? "回答已由用户终止。" : "本次回答未完整生成，已保留已有内容。");
        if (document instanceof AiResponse response) {
            for (ResponseBlock block : response.blocks()) {
                if (block instanceof TextBlock text
                        && !text.markdown().isBlank()) {
                    content.append("\n\n").append(text.markdown());
                }
            }
        }
        boolean persisted = false;
        try {
            aiChatSessionService.saveAssistantMessage(
                    request.getUserId(),
                    request.getConversationId(),
                    content.toString(),
                    runId,
                    request.getModelCode(),
                    "TEXT",
                    objectMapper.writeValueAsString(document)
            );
            persisted = true;
        } catch (Exception persistenceException) {
            log.error("保存最终快照失败，runId={}，errorType={}",
                    runId, persistenceException.getClass().getSimpleName());
        }
        stream.finishInterruptedResponse(document, failure, persisted);
    }

    /**
     * 保留当前回答模式和已有业务数据，整理取消或失败快照。
     */
    private ResponseDocument prepareInterruptedResponse(AgentStreamSession stream, boolean cancelled) {
        ReportSchema report = stream.getCurrentReport();

        if (report != null) {
            List<ReportSection> sections = new ArrayList<>();

            for (ReportSection section : report.sections()) {
                List<ResponseBlock> blocks = new ArrayList<>();

                for (ResponseBlock block : section.blocks()) {
                    if (block instanceof StatusBlock statusBlock && "report-analysis-status".equals(statusBlock.id()) && "PENDING".equals(statusBlock.code())) {
                        // 只修改报告分析的运行提示，不修改审批等业务状态。
                        blocks.add(new StatusBlock(
                                statusBlock.id(),
                                statusBlock.title(),
                                statusBlock.order(),
                                BlockStatus.READY,
                                statusBlock.source(),
                                cancelled ? "CANCELLED" : "FAILED",
                                cancelled ? "AI分析已终止" : "AI分析未完成",
                                cancelled ? Tone.MUTED : Tone.WARNING
                        ));
                    } else if (block instanceof TextBlock text && (text.status() == BlockStatus.STREAMING
                            || text.status() == BlockStatus.PENDING)) {
                        blocks.add(new TextBlock(
                                text.id(),
                                text.title(),
                                text.order(),
                                cancelled ? BlockStatus.CANCELLED : BlockStatus.FAILED,
                                text.source(),
                                text.markdown()
                        ));
                    } else {
                        blocks.add(block);
                    }
                }
                sections.add(new ReportSection(section.id(), section.title(), section.order(), blocks));
            }

            return new ReportSchema(
                    report.schemaVersion(),
                    report.responseId(),
                    report.runId(),
                    report.conversationId(),
                    report.mode(),
                    cancelled ? ResponseStatus.CANCELLED : ResponseStatus.PARTIAL,
                    report.dataComplete(),
                    report.reportId(),
                    report.reportType(),
                    report.queryType(),
                    report.title(),
                    report.subtitle(),
                    sections,
                    report.references(),
                    report.meta()
            );
        }
        AiResponse response = cancelled
                ? stream.prepareCancelledChatResponse(null)
                : stream.getChatResponseAccumulator().fail();
        List<ResponseBlock> blocks = new ArrayList<>();
        boolean visible = false;
        for (ResponseBlock block : response.blocks()) {
            if (block instanceof TextBlock text) {
                // 复用现有过滤，避免异常快照保存内部执行过程信息。
                String markdown = sanitizeUserVisibleAnswer(text.markdown());
                blocks.add(new TextBlock(
                        text.id(), text.title(), text.order(),
                        text.status(), text.source(), markdown
                ));
                visible |= !markdown.isBlank();
            } else {
                blocks.add(block);
                visible |= block.status() == BlockStatus.READY;
            }
        }
        ResponseStatus status = cancelled ? ResponseStatus.CANCELLED : (visible ? ResponseStatus.PARTIAL : ResponseStatus.FAILED);
        return new AiResponse(response.schemaVersion(), response.responseId(), response.runId(), response.conversationId(), response.mode(), status, response.dataComplete(), response.context(), blocks, response.references(), response.meta());
    }

    /**
     * 判断异常链中是否包含主动终止信号。
     */
    private boolean isRunCancelled(
            Throwable throwable) {

        if (Thread.currentThread().isInterrupted()) {
            return true;
        }

        Throwable current = throwable;

        while (current != null) {
            if (current instanceof CancellationException
                    || current instanceof InterruptedException) {
                return true;
            }

            if (current.getCause() == current) {
                break;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * 查找客户端断开异常。
     */
    private AgentClientDisconnectedException findClientDisconnected(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AgentClientDisconnectedException disconnected) {
                return disconnected;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }

        return null;
    }

    /**
     * 处理报告后的确定性业务追问。
     */
    private void handleReportFollowUp(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            ReportFollowUpDecision decision)
            throws Exception {

        switch (decision.status()) {
            case CANCELLED, CLARIFY -> {
                publishAssistantAnswer(request, stream, runId, decision.message());
            }
            case READY -> executeReadyReportFollowUp(request, stream, runId, decision);
            case NONE -> throw new IllegalStateException(
                    "无追问状态不能进入追问执行逻辑"
            );
        }
    }

    /**
     * 执行唯一匹配后的报告追问。
     */
    private void executeReadyReportFollowUp(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            ReportFollowUpDecision decision)
            throws Exception {

        /*
         * 当前阶段只接入直接只读能力。
         * 多节点 WORKFLOW 等出现真实需求后再接入。
         */
        if (!"CAPABILITY".equalsIgnoreCase(
                decision.targetType())) {

            publishAssistantAnswer(request, stream, runId,
                    "当前追问目标暂不支持直接执行，请重新发起完整业务查询。");
            return;
        }

        runTraceService.updateRouteType(runId, RouteType.BUSINESS_QUERY);
        RoutePlan routePlan =planTemplateRegistry
                        .buildReportFollowUpCapabilityPlan(
                                runId,
                                request,
                                decision
                        );
        stream.send(
                "plan",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.PLAN.name(),
                        "已生成业务明细查询计划。",
                        routePlan
                )
        );

        /*
         * 复用现有执行、审计、字段投影和回答链路。
         *
         * 成功后 recordToolResult 会覆盖旧会话状态，
         * 从而自然清除 pendingReportFollowUp。
         */
        executeToolPlan(
                request,
                stream,
                runId,
                routePlan
        );
    }

    /**
     * 基于上一轮快照回答追问，不重新执行工作流或覆盖原报告。
     */
    private void executeResultAnalysis(AgentRequest request, AgentStreamSession stream, String runId) throws Exception {

        ResultArtifactAnalysisResult result = resultArtifactAnalysisService.analyze(request, runId);
        if (result.factSet() == null) {
            // 确定性说明也必须保留原数据完整性，不能统一设置为true。
            publishAssistantAnswer(request, stream, runId, result.answer(), result.dataComplete());
            return;
        }

        Map<String, Object> modelInput = new LinkedHashMap<>(safeModelInputBuilder.build(result.factSet()));

        // 事实条数、汇总结果条数都不能直接解释为项目数量。
        modelInput.remove("recordCount");
        modelInput.put(
                "resultKind",
                result.narrativeOnly() ? "ANALYSIS" : "STATISTICS"
        );
        modelInput.put(
                "scopeDescription",
                result.narrativeOnly()
                        ? "基于上一轮已保存且允许分析的业务事实进行解释。"
                          + "不得补造快照中未提供的公式结果、风险依据或额外业务数据。"
                        : "基于上一轮结果快照的确定性统计。"
                          + "只解释已计算结果和统计口径，不重新计算，不推断项目数量。"
        );

        List<String> unknownObjectIds = result.narrativeOnly()
                ? request.getUnknownObjectIds()
                : List.of();

        modelInput.put(
                "dataComplete",
                result.dataComplete()
                        && result.factSet().dataComplete()
                        && (unknownObjectIds == null || unknownObjectIds.isEmpty())
        );

        BusinessTextFacts facts = new BusinessTextFacts(
                result.factSet(),
                List.of(),
                List.of(),
                unknownObjectIds,
                modelInput
        );

        businessTextAnswerService.streamAnswer(
                request,
                stream,
                runId,
                request.getPreviousWorkflowCode(),
                request.getResultArtifactId(),
                facts,
                result.narrativeOnly()
        );
    }

    /**
     * 执行业务能力计划。
     *
     * 能力执行结果统一转换为业务事实，
     * 再通过Block协议和SSE输出。
     */
    private void executeToolPlan(AgentRequest request, AgentStreamSession stream,
                                 String runId, RoutePlan routePlan) throws Exception {

        stream.send(
                "thinking",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.THINKING.name(),
                        "正在查询业务数据。",
                        null
                )
        );

        ToolExecutionContext toolContext =
                ToolExecutionContext.builder()
                        .runId(runId)
                        .userId(request.getUserId())
                        .userContext(request.getPageContext())
                        .authorization(request.getAuthorization())
                        .variables(new LinkedHashMap<>())
                        .build();

        List<ToolResult> toolResults =
                toolExecutor.executePlan(
                        toolContext,
                        routePlan
                );

        stream.send(
                "tool_result",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.TOOL_RESULT.name(),
                        "业务数据查询完成。",
                        null
                )
        );

        ToolResult failedResult =
                findFirstFailedResult(toolResults);

        if (failedResult != null) {
            publishAssistantAnswer(
                    request,
                    stream,
                    runId,
                    buildFailedAnswer(failedResult)
            );
            return;
        }

        BusinessTextFacts facts =
                capabilityAnswerFactBuilder.build(
                        toolResults
                );

        /*
         * 在模型调用前保存业务上下文。
         * 即使模型不可用，后续追问仍能继承本轮查询条件。
         */
        conversationStateRecorder.recordToolResult(
                request,
                routePlan,
                runId,
                toolResults
        );
        // 普通能力查询继续展示业务区块。
        businessTextAnswerService.streamAnswer(request, stream, runId, null, null, facts, false);
    }
    /**
     * 查找第一个失败的工具结果。
     */
    private ToolResult findFirstFailedResult(List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return null;
        }

        return toolResults.stream()
                .filter(result -> !result.isSuccess())
                .findFirst()
                .orElse(null);
    }

    /**
     * 执行企业知识库RAG问答。
     *
     * 回答增量交给当前Agent SSE会话发送，
     * 不创建第二条SSE连接。
     */
    private KnowledgeDocumentQueryResponse executeRagQuery(AgentRequest request, String runId, Consumer<String> deltaConsumer) {

        KnowledgeDocumentQueryRequest ragRequest =
                new KnowledgeDocumentQueryRequest(
                        request.getCategoryIds(),
                        request.getDocumentIds(),
                        request.getEffectiveQuestion(),
                        request.getTopK(),
                        request.getMinScore()
                );

        ModelCallContext ragContext =
                ModelCallContext.builder()
                        .runId(runId)
                        .conversationId(
                                request.getConversationId()
                        )
                        .userId(
                                request.getUserId()
                        )
                        .callType(
                                ModelCallType.RAG
                        )
                        // 只切换RAG回答模型，不影响Embedding模型
                        .modelCode(
                                request.getModelCode()
                        )
                        .callSequence(1)
                        .build();

        return knowledgeDocumentQueryService.query(
                ragRequest,
                ragContext,
                request.getConversationMemory(),
                request.getKnowledgeAccessPrincipal(),
                deltaConsumer
        );
    }

    /**
     * 构建工具失败回答。
     *
     * 第一版不用大模型总结，直接返回结构化错误，方便排查。
     */
    private String buildFailedAnswer(ToolResult failedResult) {
        return "业务能力调用失败："
                + failedResult.getErrorMessage()
                + "。错误码："
                + failedResult.getErrorCode()
                + "。能力编码："
                + failedResult.getCapabilityCode();
    }

    /**
     * 验证请求参数
     *
     * @param request 请求参数
     */
    private void validateRequest(AgentRequest request) {
        if (request == null || !StringUtils.hasText(request.getUserQuestion())) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
    }

    /**
     * 执行纯RAG问答。
     *
     * 模型回答通过统一BLOCK_DELTA事件实时输出，
     * 引用信息放入最终AiResponse，不再单独发送旧REFERENCES事件。
     */
    private void executeRagOnly(AgentRequest request, AgentStreamSession stream, String runId, RoutePlan routePlan) throws Exception {
        long startedAt = System.currentTimeMillis();
        StringBuilder streamedAnswer = new StringBuilder();
        stream.startChatResponse();
        stream.startTextResponse("rag_answer", "知识库回答", 0, BlockSource.AI);
        try {
            KnowledgeDocumentQueryResponse ragResponse =
                    executeRagQuery(request, runId, delta -> {
                            stream.checkCancellation();
                                try {
                                    streamedAnswer.append(delta);
                                    stream.appendTextResponse(delta);
                                } catch (Exception exception) {
                                    throw new IllegalStateException(
                                            "发送知识库回答增量失败",
                                            exception
                                    );
                                }
                            }
                    );

            String visibleAnswer = sanitizeUserVisibleAnswer(ragResponse.answer());

            if (!StringUtils.hasText(visibleAnswer)) {
                visibleAnswer = "本次知识库查询没有生成可展示内容。";
            }

            /*
             * 正常情况下RAG服务已经推送过增量。
             * 该分支只处理模型没有产生增量的降级场景。
             */
            if (!StringUtils.hasText(streamedAnswer)) {
                stream.appendTextResponse(visibleAnswer);
            }
            stream.finishTextResponse(visibleAnswer);
            List<ResponseReference> references = new ArrayList<>();

            List<KnowledgeDocumentQueryResponse.Reference> sourceReferences =
                    ragResponse.references() == null
                            ? List.of()
                            : ragResponse.references();

            for (int index = 0; index < sourceReferences.size(); index++) {
                KnowledgeDocumentQueryResponse.Reference reference = sourceReferences.get(index);
                references.add(
                        new ResponseReference(
                                "rag_reference_" + (index + 1),
                                Objects.toString(
                                        reference.documentId(),
                                        ""
                                ),
                                Objects.toString(
                                        reference.versionId(),
                                        ""
                                ),
                                Objects.toString(
                                        reference.chunkId(),
                                        ""
                                ),
                                reference.documentTitle(),
                                reference.source(),
                                ""
                        )
                );
            }
            stream.setResponseReferences(references);
            stream.setResponseDataComplete(true);
            String effectiveModelCode = resolveEffectiveModelCode(request, runId);

            boolean fallbackUsed = StringUtils.hasText(request.getModelCode())
                    && !Objects.equals(request.getModelCode(), effectiveModelCode);
            stream.setResponseMeta(new ResponseMeta(
                            "",
                            "",
                            request.getModelCode(),
                            effectiveModelCode,
                            fallbackUsed,
                            System.currentTimeMillis()
                                    - startedAt,
                            0,
                            0,
                            0,
                            0,
                            false
                    )
            );
            // 保留上下文记录；如果这里失败，不能先保存一份“成功”快照再改写它。
            stream.checkCancellation();
            conversationStateRecorder.recordRagResult(request, runId);
            // 完成与取消在最终保存前确定，避免保存后再接受取消。
            stream.beginFinalization();
            AiResponse finalResponse = stream.getChatResponseAccumulator().complete();
            aiChatSessionService.saveAssistantMessage(
                    request.getUserId(),
                    request.getConversationId(),
                    visibleAnswer,
                    runId,
                    effectiveModelCode,
                    "TEXT",
                    objectMapper.writeValueAsString(finalResponse)
            );
            stream.finishChatResponse();

        } catch (Exception exception) {
            // 同时检查用户取消标志，避免底层组件清除线程中断后漏判。
            if (stream.isCancellationRequested() || isRunCancelled(exception)) {
                // 清除中断标志，允许保存部分回答。
                Thread.interrupted();
                String partialAnswer = sanitizeUserVisibleAnswer(streamedAnswer.toString());
                String cancelledAnswer = StringUtils.hasText(partialAnswer)
                        ? partialAnswer + "\n\n回答已由用户终止。"
                        : "回答已由用户终止。";
                stream.setResponseDataComplete(false);
                stream.setResponseMeta(new ResponseMeta(
                        "",
                        "",
                        request.getModelCode(),
                        request.getModelCode(),
                        false,
                        System.currentTimeMillis() - startedAt,
                        0,
                        0,
                        0,
                        0,
                        false
                ));
                // 使用清理后的文字生成取消快照，不提前发送 READY 区块。
                AiResponse cancelledResponse =
                        stream.prepareCancelledChatResponse(partialAnswer);

                aiChatSessionService.saveAssistantMessage(
                        request.getUserId(),
                        request.getConversationId(),
                        cancelledAnswer,
                        runId,
                        request.getModelCode(),
                        "TEXT",
                        objectMapper.writeValueAsString(cancelledResponse)
                );
                stream.cancelChatResponse();
                CancellationException cancelledException = new CancellationException("回答已由用户终止");
                cancelledException.initCause(exception);
                throw cancelledException;
            }
            throw exception;
        }
    }
    /**
     * 向前端发送WRITE参数收集表单。
     *
     * 此方法只发送Schema和初始值，
     * 不创建PendingAction，不调用WRITE接口。
     */
    /**
     * 向前端发送 WRITE 参数收集表单，同时保存助手提示语。
     */
    private void sendActionForm(AgentRequest request, AgentStreamSession stream,String runId,
                                WorkflowPlan workflowPlan) throws Exception {
        ActionFormVO form = ActionFormVO.builder()
                .workflowCode(workflowPlan.getWorkflowCode())
                .workflowVersionId(workflowPlan.getVersionId())
                .capabilityCode(workflowPlan.getActionCapabilityCode())
                .capabilityVersionId(workflowPlan.getActionCapabilityVersionId())
                .capabilityName(workflowPlan.getActionCapabilityName())
                .schema(workflowPlan.getActionInputSchema().toString())
                .initialValue(new LinkedHashMap<>(workflowPlan.getInput()))
                .clarifyQuestion(workflowPlan.getClarifyQuestion())
                .build();

        String messageContent = StringUtils.hasText(workflowPlan.getClarifyQuestion())
                ? workflowPlan.getClarifyQuestion()
                : "请填写操作所需的信息。";

        //  保存表单提示语和当时使用的发布版本表单快照。
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                messageContent,
                runId,
                request.getModelCode(),
                "ACTION_FORM",
                objectMapper.writeValueAsString(form)
        );
        stream.send(
                "action_form",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.ACTION_FORM.name(),
                        messageContent,
                        form
                )
        );
    }
    /**
     * 向聊天端发送写操作预览。
     */
    private void sendActionPreview( AgentRequest request,AgentStreamSession stream,
                                    String runId,
                                    DynamicCapabilityPlan plan,
                                    PendingAction pendingAction) throws Exception {
        // 操作参数必须读取数据库中的待确认记录，
        // 避免前端依赖或修改 Agent 内部的规划对象。
        Map<String, Object> input = objectMapper.readValue(
                pendingAction.getInputJson(),
                new TypeReference<>() {
                }
        );
        ActionPreviewVO preview = ActionPreviewVO.builder()
                .runId(runId)
                .capabilityCode(pendingAction.getCapabilityCode())
                .capabilityName(pendingAction.getCapabilityName())
                .actionSummary(pendingAction.getActionSummary())
                .input(input)
                .status(pendingAction.getStatus())
                .expireAt(pendingAction.getExpireAt())
                .requireConfirm(true)
                .displayInput(plan.getDisplayInput())
                .build();
        StringBuilder markdown = new StringBuilder();
        markdown.append("## 操作确认\n\n")
                .append("即将执行：**")
                .append(escapeMarkdown(pendingAction.getCapabilityName()))
                .append("**\n\n");
        if (StringUtils.hasText(pendingAction.getActionSummary())) {
            markdown.append(pendingAction.getActionSummary())
                    .append("\n\n");
        }
        markdown.append("请确认以上操作是否继续执行。");
        String messageContent = markdown.toString();

        //  保存操作确认文字和待确认操作快照。
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                messageContent,
                runId,
                request.getModelCode(),
                "ACTION_PREVIEW",
                objectMapper.writeValueAsString(preview)
        );
        // data 只返回稳定的预览 VO，不再暴露 DynamicCapabilityPlan。
        stream.send(
                "action_preview",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.ACTION_PREVIEW.name(),
                        markdown.toString(),
                        preview
                )
        );
    }

    /**
     * 转义 Markdown 表格中的特殊字符。
     */
    private String escapeMarkdown(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ");
    }


    private WorkflowExecutionOutcome executeWorkflowQuery(AgentRequest request,
                                                          AgentStreamSession stream, String runId, IntentResult intentResult) throws Exception {

        WorkflowPlan plan =intentResult.getWorkflowPlan();
        ReportQueryType queryType =intentResult.getQueryType() == null
                        ? ReportQueryType.DATA_QUERY
                        : intentResult.getQueryType();
        if (plan == null || !plan.isReady()) {
            throw new IllegalStateException(
                    "缺少可执行工作流计划"
            );
        }
        stream.send(
                "thinking",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.THINKING.name(), "正在执行工作流：" + plan.getWorkflowName(),
                        null)
        );

        WorkflowExecutionCommand command =WorkflowExecutionCommand.builder()
                        .runId(runId)
                        .userId(request.getUserId())
                        .workflowCode(plan.getWorkflowCode())
                        .expectedVersionId(plan.getVersionId())
                        /*
                         * 这里只允许使用Planner清洗后的input。
                         * 不读取request.extra中的workflowCode或input。
                         */
                        .input(plan.getInput())
                        .userContext(request.getPageContext() == null ? new LinkedHashMap<>()
                                : new LinkedHashMap<>(request.getPageContext()))
                        .authorization(request.getAuthorization())
                        .secureContext(new LinkedHashMap<>())
                        .build();

        WorkflowExecutionOutcome outcome = workflowExecutionFacade.execute(command);
        // 聊天进度只发送执行摘要，不发送业务结果和逐条执行数据。
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("runId", outcome.runId());
        progress.put("workflowCode", outcome.workflowCode());
        progress.put("workflowName", outcome.workflowName());
        progress.put("versionNo", outcome.versionNo());
        progress.put("success", outcome.success());
        progress.put("partialSuccess", outcome.partialSuccess());
        progress.put("durationMs", outcome.durationMs());
        progress.put("errorMessage", outcome.success() ? "" : "业务查询失败，请根据运行编号查看执行详情。");
    // 保留前端需要的批次计数，不返回包含内部字段的 items。
        progress.put("batches", outcome.batches().stream()
                        .map(batch -> Map.of(
                                "totalCount", batch.totalCount(),
                                "successCount", batch.successCount(),
                                "partialCount", batch.partialCount(),
                                "failureCount", batch.failureCount(),
                                "skippedCount", batch.skippedCount(),
                                "partialSuccess", batch.partialSuccess()
                        ))
                        .toList()
        );

        stream.send(
                "workflow_result",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.WORKFLOW_RESULT.name(),
                        outcome.success() ? "工作流执行完成。" : "工作流执行失败。",
                        progress
                )
        );
        /*
         * 工作流执行失败时没有可用于生成报告的基础数据，
         * 直接返回真实业务错误，禁止继续准备报告或执行分析兜底。
         */
        if (!outcome.success()) {
            String errorMessage =StringUtils.hasText(outcome.errorMessage())? outcome.errorMessage() : "工作流执行失败";
            log.warn("工作流执行失败，runId={}，workflowCode={}，errorCode={}，errorMessage={}", runId, outcome.workflowCode(), outcome.errorCode(), errorMessage);
            publishAssistantAnswer(request, stream, runId, "查询失败：" + errorMessage);
            return outcome;
        }

        /*
         * 所有工作流查询默认使用CHAT回答。
         *
         * reportDefinition只代表当前工作流具备报告生成能力，
         * 不能自动把普通查询切换成REPORT。
         */
        PresentationMode presentationDecision = PresentationMode.CHAT;
        try {
            presentationDecision = workflowAnswerPolicyResolver.resolve(outcome)
                    .decide(request.getEffectiveQuestion());
        } catch (RuntimeException exception) {
            /*
             * 报告配置读取失败时安全降级为CHAT，
             * 不能因为配置异常阻断普通业务问答。
             */
            log.warn("工作流报告策略解析失败，已降级为CHAT，" + "runId={}，workflowCode={}，errorType={}",
                    runId, outcome.workflowCode(), exception.getClass().getSimpleName());
        }
        if (presentationDecision == PresentationMode.CHAT) {
            executeWorkflowTextAnswer(request, stream, runId, plan, outcome);
            return outcome;
        }
        /*
         * 只有明确要求生成报告，并且当前发布版本配置了报告模板，
         * 才会继续执行下面的ReportSchema构建逻辑。
         */
        WorkflowAnswerPreparation preparation = null;
        try {
            preparation =workflowAnswerComposer.prepareReport(request,outcome);
        } catch (RuntimeException exception) {
            log.warn(
                    "报告基础数据准备失败，runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName()
            );
        }
        ReportSchemaVO baseReportSchema =reportSchemaBuilder.build(outcome,
                        preparation == null ? null: preparation.artifactId(),
                        queryType);
        boolean analysisRequired = baseReportSchema.analysis().requiresExecution();

        String baseMessageContent = analysisRequired
                        ? "基础报告已生成，正在进行AI分析。"
                        : "业务报告已生成。";

        /*
         * 基础业务数据完成后立即构建统一报告快照。
         * 此时整体状态仍为RUNNING，不等待AI分析完成。
         */
        ReportSchema baseReportResponse =
                buildReportResponse(
                        request,
                        stream,
                        runId,
                        outcome.workflowCode(),
                        baseReportSchema,
                        ResponseStatus.RUNNING,
                        request.getModelCode()
                );

        /*
         * 先保存基础报告。
         * 页面刷新、连接中断或者AI分析失败时，
         * 仍然可以从聊天记录恢复基础业务数据。
         */
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                baseMessageContent,
                runId,
                request.getModelCode(),
                "TEXT",
                objectMapper.writeValueAsString(
                        baseReportResponse
                )
        );
        stream.sendReportSnapshot(baseReportResponse);

        if (preparation != null) {
            BusinessTextFacts contextFacts = null;

            try {
                /*
                 * 上下文事实构建失败不能影响基础报告展示。
                 */
                contextFacts = workflowTextFactBuilder.build(preparation);
            } catch (RuntimeException exception) {
                log.warn(
                        "报告会话上下文事实构建失败，"
                                + "runId={}，workflowCode={}，"
                                + "errorType={}",
                        runId,
                        outcome.workflowCode(),
                        exception.getClass().getSimpleName(),
                        exception
                );
            }
            conversationStateRecorder.recordWorkflowResult(request, plan, outcome, runId, preparation.artifactId(), contextFacts, "REPORT");
        }

        /*
         * 两种情况需要继续分析：
         * 1. 用户明确要求分析；
         * 2. 普通查询开启了智能分析判定。
         */
        boolean decisionEnabled = analysisProperties.isDecisionEnabled() && !analysisRequired;

        if (!analysisRequired && !decisionEnabled) {
            completeDataQueryReport(request, stream, runId, outcome.workflowCode(), baseReportSchema);
            return outcome;
        }
        if (preparation == null) {
            if (analysisRequired) {
                completeReportWithRuleFallback(request, stream, runId, outcome.workflowCode(), baseReportSchema, new IllegalStateException("报告基础数据准备失败"), System.currentTimeMillis());
            } else {
                completeDataQueryReport(request, stream, runId, outcome.workflowCode(), baseReportSchema);
            }

            return outcome;
        }

        /*
         * 当前方法已经运行在主聊天任务中。
         * 报告分析必须受当前任务控制，
         * 保证用户可以主动终止。
         */
        analyzeWorkflowReportAsync(request, stream, runId, outcome, preparation, baseReportSchema);
        return outcome;
    }

    /**
     * 执行工作流普通文字回答。
     *
     * 本方法不创建ReportSchema，
     * 普通文字回答只生成AiResponse，不构建固定报告结构。
     */
    private void executeWorkflowTextAnswer(AgentRequest request, AgentStreamSession stream, String runId, WorkflowPlan plan, WorkflowExecutionOutcome outcome) throws Exception {

        WorkflowAnswerPreparation preparation;
        try {
            preparation = workflowAnswerComposer.prepare(request, outcome, "ANSWER");
        } catch (RuntimeException exception) {

            log.warn("工作流文字回答安全数据准备失败，"
                            + "runId={}，workflowCode={}，"
                            + "errorType={}",
                    runId,
                    outcome.workflowCode(),
                    exception.getClass().getSimpleName()
            );

            /*
             * 安全投影失败时不能把原始业务数据发给模型。
             */
            publishAssistantAnswer(request, stream, runId,
                    "查询已经完成，但字段展示策略加载失败。"
                            + "为保护业务数据，本次未生成详细回答，"
                            + "请管理员检查字段字典发布状态。");
            return;
        }
        BusinessTextFacts facts = workflowTextFactBuilder.build(preparation);
        /*
         * 在模型调用前保存Artifact上下文。
         * 即使模型失败，下一轮仍可继续基于本次结果追问。
         */
        conversationStateRecorder.recordWorkflowResult(request, plan, outcome, runId, preparation.artifactId(), facts, "ANSWER");
        // 普通工作流查询保持原有展示方式。
        businessTextAnswerService.streamAnswer(request, stream, runId, outcome.workflowCode(), preparation.artifactId(), facts, false);
    }


    /**
     * 完成不需要AI分析的普通数据报告。
     */
    private void completeDataQueryReport(AgentRequest request, AgentStreamSession stream, String runId, String workflowCode, ReportSchemaVO reportSchema) throws Exception {
        // 先确定最终保存归属，已接受的取消必须优先进入取消收尾。
        stream.beginFinalization();
        String effectiveModelCode = resolveEffectiveModelCode(request, runId);

        ReportSchema completedResponse = buildReportResponse(
                        request,
                        stream,
                        runId,
                        workflowCode,
                        reportSchema,
                        ResponseStatus.COMPLETED,
                        effectiveModelCode
                );
        updateReportMessage(
                request,
                stream,
                runId,
                "业务报告已生成。",
                effectiveModelCode,
                completedResponse
        );

        /*
         * 追问仍然保持现有行为。
         * 后续清理旧协议阶段再统一迁移追问事件。
         */
        publishReportFollowUpPrompt(request, stream, runId);
        stream.finishReportResponse(completedResponse);
    }

    /**
     * 生成报告AI分析。
     */
    private void analyzeWorkflowReportAsync(AgentRequest request, AgentStreamSession stream,
                                            String runId, WorkflowExecutionOutcome outcome,
                                            WorkflowAnswerPreparation preparation, ReportSchemaVO baseReportSchema) throws Exception {
        long analysisStartedAt = System.currentTimeMillis();
        ReportSchemaVO analysisReportSchema = baseReportSchema;
        try {
            // 进入模型分析前再次检查，避免前面的组件清除中断标志后继续分析。
            stream.checkCancellation();
            /*
             * 普通数据查询开启智能判定时，
             * 先判断当前业务数据是否需要AI分析。
             */
            if (analysisProperties.isDecisionEnabled() && !baseReportSchema.analysis().requiresExecution()) {

                boolean needAnalysis = workflowAnswerAnalysisDecider.decide(
                                request,
                                runId,
                                preparation
                        );

                if (!needAnalysis) {
                    completeDataQueryReport(request, stream, runId, outcome.workflowCode(), baseReportSchema);
                    return;
                }

                /*
                 * 普通查询经判定需要分析时，
                 * 将报告分析状态更新为PENDING。
                 */
                analysisReportSchema = reportSchemaBuilder.withAnalysis(baseReportSchema, ReportSchemaVO.Analysis.pending());

                ReportSchema runningResponse = buildReportResponse(
                                request,
                                stream,
                                runId,
                                outcome.workflowCode(),
                                analysisReportSchema,
                                ResponseStatus.RUNNING,
                                request.getModelCode()
                        );

                updateReportMessage(
                        request,
                        stream,
                        runId,
                        "基础报告已生成，正在进行AI分析。",
                        request.getModelCode(),
                        runningResponse
                );
                stream.sendReportSnapshot(
                        runningResponse
                );
            }

            WorkflowAnswerAnalysisResult result = analyzeReportWithTimeout(
                            request,
                            preparation,
                            analysisReportSchema
                    );

            completeAnalyzedReport(
                    request,
                    stream,
                    runId,
                    outcome.workflowCode(),
                    analysisReportSchema,
                    result.analysis()
            );
            agentMetrics.recordReportAnalysisCompleted(
                    "AI",
                    "NONE",
                    System.currentTimeMillis()
                            - analysisStartedAt
            );
        } catch (Exception exception) {
            // 同时检查用户取消标志，避免底层组件清除线程中断后漏判。
            if (stream.isCancellationRequested() || isRunCancelled(exception)) {
                Thread.interrupted();
                completeCancelledReport(
                        request,
                        stream,
                        runId,
                        outcome.workflowCode(),
                        analysisReportSchema
                );
                throw new CancellationException(
                        "回答已由用户终止"
                );
            }
            completeReportWithRuleFallback(
                    request,
                    stream,
                    runId,
                    outcome.workflowCode(),
                    analysisReportSchema,
                    exception,
                    analysisStartedAt
            );
        }
    }

    /**
     * 用户终止AI分析后保留基础业务报告。
     */
    private void completeCancelledReport(AgentRequest request, AgentStreamSession stream, String runId, String workflowCode, ReportSchemaVO baseReportSchema) throws Exception {
        // 取消已经确定，后续数据库收尾不再接受新的中断。
        stream.beginInterruptedFinalization();
        Thread.interrupted();
        ReportSchemaVO.Analysis cancelledAnalysis =
                new ReportSchemaVO.Analysis(
                        "CANCELLED",
                        "SYSTEM",
                        "AI分析已由用户终止，基础业务数据仍然有效。",
                        List.of(),
                        List.of(),
                        List.of()
                );

        ReportSchemaVO cancelledReportSchema =
                reportSchemaBuilder.withAnalysis(
                        baseReportSchema,
                        cancelledAnalysis
                );

        ReportSchema cancelledResponse =
                buildReportResponse(
                        request,
                        stream,
                        runId,
                        workflowCode,
                        cancelledReportSchema,
                        ResponseStatus.CANCELLED,
                        request.getModelCode()
                );

        updateReportMessage(
                request,
                stream,
                runId,
                "基础报告已生成，AI分析已由用户终止。",
                request.getModelCode(),
                cancelledResponse
        );

        stream.cancelReportResponse(
                cancelledResponse
        );
    }

    /**
     * 带超时保护的报告分析调用。
     */
    private WorkflowAnswerAnalysisResult analyzeReportWithTimeout(AgentRequest request, WorkflowAnswerPreparation preparation, ReportSchemaVO baseReportSchema) throws Exception {

        Future<WorkflowAnswerAnalysisResult> future = workflowAnswerAnalysisExecutor.submit(
                        () -> workflowAnswerComposer.analyzeReport(request, preparation, baseReportSchema));

        try {
            return future.get(analysisProperties.getTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            /*
             * 用户主动终止主聊天任务时，
             * 同时取消独立线程池中的报告分析任务。
             */
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (TimeoutException exception) {
            /*
             * 分析超时只取消内部分析任务，
             * 不能中断主聊天线程，否则会被误判为用户主动终止。
             */
            future.cancel(true);
            throw new IllegalStateException("AI分析超过" + analysisProperties.getTimeoutSeconds() + "秒未完成，已保留基础业务报告", exception);
        }
    }

    /**
     * 完成AI分析或者规则兜底分析。
     *
     * 先保存完整报告，再发送完成事件，
     * 保证客户端断开后仍然可以恢复报告。
     */
    private void completeAnalyzedReport(AgentRequest request, AgentStreamSession stream,
                                        String runId, String workflowCode, ReportSchemaVO baseSchema,
                                        ReportSchemaVO.Analysis analysis) throws Exception {
        // 先确定最终保存归属，已接受的取消必须优先进入取消收尾。
        stream.beginFinalization();
        ReportSchemaVO finalSchema =
                reportSchemaBuilder.withAnalysis(
                        baseSchema,
                        analysis
                );

        String effectiveModelCode =
                resolveEffectiveModelCode(
                        request,
                        runId
                );

        ReportSchema completedResponse =
                buildReportResponse(
                        request,
                        stream,
                        runId,
                        workflowCode,
                        finalSchema,
                        ResponseStatus.COMPLETED,
                        effectiveModelCode
                );

        updateReportMessage(
                request,
                stream,
                runId,
                "基础报告和数据分析已生成。",
                effectiveModelCode,
                completedResponse
        );

        publishReportFollowUpPrompt(
                request,
                stream,
                runId
        );

        stream.finishReportResponse(
                completedResponse
        );
    }

    /**
     * 模型调用、结果解析或者分析准备失败时，
     * 使用后端规则分析进行兜底。
     */
    private void completeReportWithRuleFallback(AgentRequest request, AgentStreamSession stream,
                                                String runId, String workflowCode, ReportSchemaVO baseSchema,
                                                Exception exception, long analysisStartedAt) {
        // 用户取消不属于分析失败，不能继续执行兜底分析。
        stream.checkCancellation();
        Throwable rootCause = findRootCause(exception);

        /*
         * 客户端断开不属于报告分析失败，
         * 不能覆盖已经保存的基础报告。
         */
        if (rootCause instanceof AgentClientDisconnectedException) {
            log.info("客户端连接已断开，报告分析结果无法推送，runId={}", runId);
            return;
        }

        String fallbackReason = classifyFallbackReason(rootCause);

        log.warn(
                "报告AI分析未完成，使用规则分析兜底，" + "runId={}，reason={}，errorType={}",
                runId,
                fallbackReason,
                rootCause.getClass().getSimpleName()
        );

        try {
            ReportAnalysisInput input = reportAnalysisInputBuilder.build(baseSchema);

            ReportSchemaVO.Analysis fallbackAnalysis = reportAnalysisFallbackService.build(input);

            completeAnalyzedReport(request, stream, runId, workflowCode, baseSchema, fallbackAnalysis);

            agentMetrics.recordReportAnalysisCompleted(
                    "RULE_FALLBACK",
                    fallbackReason,
                    System.currentTimeMillis()
                            - analysisStartedAt
            );
        } catch (Exception fallbackException) {
            if (stream.isCancellationRequested() || isRunCancelled(fallbackException)) {
                CancellationException cancelled =
                        new CancellationException("回答已由用户终止");
                cancelled.initCause(fallbackException);
                throw cancelled;
            }
            Throwable fallbackRootCause = findRootCause(fallbackException);
            if (fallbackRootCause instanceof AgentClientDisconnectedException) {
                log.info("客户端连接已断开，规则分析结果无法推送，runId={}", runId);
                return;
            }
            log.error("报告规则分析兜底异常，" + "runId={}，errorType={}", runId, fallbackRootCause.getClass().getSimpleName(), fallbackException);
            completeFailedReport(request, stream, runId, workflowCode, baseSchema, fallbackException);
        }
    }


    /**
     * AI分析和规则分析都失败时，
     * 保留已经生成成功的基础业务报告。
     */
    private void completeFailedReport(AgentRequest request, AgentStreamSession stream, String runId, String workflowCode, ReportSchemaVO baseSchema, Exception exception) {
        // 先确定最终保存归属，已接受的取消必须优先进入取消收尾。
        stream.beginFinalization();
        ReportSchemaVO.Analysis failedAnalysis = new ReportSchemaVO.Analysis(
                        "FAILED",
                        "SYSTEM",
                        "本次分析暂未完成，基础业务数据仍然可以查看。",
                        List.of(),
                        List.of(),
                        List.of()
                );

        ReportSchemaVO partialSchema =
                reportSchemaBuilder.withAnalysis(
                        baseSchema,
                        failedAnalysis
                );

        String effectiveModelCode = resolveEffectiveModelCode(request, runId);
        ReportSchema partialResponse = buildReportResponse(request, stream, runId, workflowCode, partialSchema, ResponseStatus.PARTIAL, effectiveModelCode);
        boolean persisted = false;
        try {
            updateReportMessage(
                    request,
                    stream,
                    runId,
                    "基础报告已生成，但本次分析暂未完成。",
                    effectiveModelCode,
                    partialResponse
            );
            persisted = true;
        } catch (Exception persistenceException) {
            log.error("保存部分完成报告失败，runId={}，errorType={}",
                    runId, persistenceException.getClass().getSimpleName());
        }

        stream.finishInterruptedResponse(
                partialResponse,
                exception,
                persisted
        );
    }

    /**
     * 将内部异常转换成低基数监控分类。
     *
     * 禁止把异常原文直接作为指标标签。
     */
    private String classifyFallbackReason(Throwable exception) {
        if (exception instanceof TimeoutException) {
            return "TIMEOUT";
        }
        String errorType = exception.getClass().getSimpleName();
        // Jackson解析异常的根异常消息不一定包含“JSON”或“解析”。
        if (errorType.contains("Json") || errorType.contains("MismatchedInput")) {
            return "OUTPUT_INVALID";
        }
        if ("RejectedExecutionException".equals(errorType)) {
            return "EXECUTOR_REJECTED";
        }
        String message = exception.getMessage();
        if (StringUtils.hasText(message)) {
            if (message.contains("基础数据准备")) {
                return "PREPARATION_FAILED";
            }
            if (message.contains("JSON") || message.contains("解析") || message.contains("可信内容") || message.contains("输出")) {
                return "OUTPUT_INVALID";
            }
        }
        return "MODEL_CALL_FAILED";
    }

    /**
     * 获取最底层异常。
     * 客户端断开异常是业务识别标记，不能继续解包成底层网络异常，
     * 否则会错误进入规则分析兜底并覆盖已经保存的AI结果。
     */
    private Throwable findRootCause(Throwable exception) {
        if (exception == null) {
            return new IllegalStateException("未知报告分析异常");
        }

        Throwable current = exception;

        while (current.getCause() != null && current.getCause() != current) {

            if (current instanceof AgentClientDisconnectedException) {
                return current;
            }
            current = current.getCause();
        }

        return current;
    }


    /**
     * 保存并发送报告完成后的独立助手追问。
     *
     * 追问属于增强信息，失败不能破坏已经完成的基础报告。
     */
    private void publishReportFollowUpPrompt(
            AgentRequest request,
            AgentStreamSession stream,
            String runId) {

        try {
            String prompt =reportFollowUpService.findPendingPrompt(request).orElse(null);
            if (!StringUtils.hasText(prompt)) {
                return;
            }
            /*
             * 独立保存为普通助手消息，
             * 页面刷新后可以按照聊天历史正常恢复。
             *
             * 必须在报告最终更新完成后保存，
             * 避免 updateAssistantReportMessage 按 runId 更新到追问消息。
             */
            aiChatSessionService.saveAssistantMessage(
                    request.getUserId(),
                    request.getConversationId(),
                    prompt,
                    runId,
                    request.getModelCode(),
                    "TEXT",
                    null
            );

            stream.send(
                    "report_follow_up",
                    AgentStreamEvent.builder()
                            .runId(runId)
                            .type(
                                    AgentStreamEventType
                                            .REPORT_FOLLOW_UP
                                            .name()
                            )
                            .content(prompt)
                            .data(Map.of(
                                    "prompt",
                                    prompt
                            ))
                            .presentationType("MARKDOWN")
                            .build()
            );
        } catch (Exception exception) {
            /*
             * 追问发送失败只能记录告警，
             * 不能将已经生成成功的报告改成失败。
             */
            log.warn(
                    "发送报告独立追问失败，runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }

    /**
     * 更新同一次运行保存的统一报告消息。
     */
    private void updateReportMessage(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            String content,
            String effectiveModelCode,
            ReportSchema reportResponse) throws Exception {

        aiChatSessionService.updateAssistantReportMessage(
                request.getUserId(),
                request.getConversationId(),
                runId,
                content,
                effectiveModelCode,
                objectMapper.writeValueAsString(
                        reportResponse
                )
        );
    }

    /**
     *  过滤明确属于系统执行过程的信息，保留业务结果和统计数据。
     */
    private String sanitizeUserVisibleAnswer(String answer) {
        if (!StringUtils.hasText(answer)) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        boolean skippingInternalSection = false;

        for (String line : answer.replace("\r\n", "\n").split("\n", -1)) {
            String trimmed = line.trim();

            if (trimmed.matches("^(#{1,6}\\s*)?.*跳过记录.*$")) {
                skippingInternalSection = true;
                continue;
            }
            //  分隔线只负责结束内部区段，不需要保留。
            if (skippingInternalSection && trimmed.matches("^-{3,}$")) {
                skippingInternalSection = false;
                continue;
            }

            //  新标题表示进入正常业务区段，标题本身必须继续保留。
            if (skippingInternalSection && trimmed.matches("^#{1,6}\\s+.+$")) {
                skippingInternalSection = false;
            }

            if (skippingInternalSection) {
                continue;
            }

            if (trimmed.matches(
                    ".*(?:SKIPPED_NO_ID|节点ID|能力编码|字段路径|异常堆栈|鉴权信息).*")) {
                continue;
            }

            if (trimmed.matches(
                    "^(?:工作流编码|工作流版本|运行耗时|批处理节点|Token消耗|模型名称|生成时间|数据来源)\\s*[：:].*$")) {
                continue;
            }

            //  隐藏面向内部排查的数组索引标记。
            String visibleLine = line
                    .replaceAll("[（(]\\s*索引\\s*\\d+\\s*[）)]", "")
                    .replaceAll("^(\\s*(?:#{1,6}\\s*)?)[✅⏭️⚠️❌📊]\\s*", "$1");

            result.append(visibleLine).append('\n');
        }

        return result.toString()
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
    /**
     * 获取最终生成用户可见回答的实际模型。
     *
     * 使用记录属于辅助链路，查询失败不能影响已经生成成功的回答。
     */
    private String resolveEffectiveModelCode(
            AgentRequest request,
            String runId) {

        String requestedModelCode = request.getModelCode();

        try {
            String effectiveModelCode =
                    modelUsageService
                            .findLatestSuccessfulAnswerModelCode(
                                    runId,
                                    request.getUserId()
                            );

            return StringUtils.hasText(effectiveModelCode)
                    ? effectiveModelCode
                    : requestedModelCode;
        } catch (Exception exception) {
            log.warn(
                    "查询回答实际模型失败，runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName()
            );
            return requestedModelCode;
        }
    }

    /**
     * 普通系统提示沿用默认完整性，不影响其他调用位置。
     */
    private void publishAssistantAnswer(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            String answer) throws Exception {

        publishAssistantAnswer(request, stream, runId, answer, true);
    }

    /**
     * 发布确定性文字说明，并保留来源数据的完整性。
     */
    private void publishAssistantAnswer(AgentRequest request, AgentStreamSession stream, String runId, String answer, boolean dataComplete) throws Exception {

        stream.beginFinalization();
        String visibleAnswer = sanitizeUserVisibleAnswer(answer);
        if (!StringUtils.hasText(visibleAnswer)) {
            visibleAnswer = "本次没有生成可展示的回答内容。";
        }
        String effectiveModelCode = resolveEffectiveModelCode(request, runId);
        stream.setResponseDataComplete(dataComplete);
        stream.setResponseMeta(new ResponseMeta(
                "",
                "",
                request.getModelCode(),
                effectiveModelCode,
                false,
                0,
                0,
                0,
                0,
                0,
                false
        ));
        stream.startChatResponse();
        stream.publishResponseBlock(new TextBlock("answer", "", 0, BlockStatus.READY, BlockSource.SYSTEM, visibleAnswer));

        AiResponse finalResponse = stream.getChatResponseAccumulator().complete();

        // 保存完整回答结构，刷新后仍然使用相同内容。
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                visibleAnswer,
                runId,
                effectiveModelCode,
                "TEXT",
                objectMapper.writeValueAsString(finalResponse)
        );
        stream.finishChatResponse();
    }

    /**
     * 将后台内部报告模型转换为最终REPORT协议。
     */
    private ReportSchema buildReportResponse(AgentRequest request, AgentStreamSession stream,
                                             String runId, String workflowCode, ReportSchemaVO reportSchema,
                                             ResponseStatus status, String effectiveModelCode) {

        ReportSchemaVO.Meta reportMeta = reportSchema.meta();
        String requestedModelCode = request.getModelCode();

        String actualModelCode =
                StringUtils.hasText(effectiveModelCode)
                        ? effectiveModelCode
                        : requestedModelCode;

        boolean fallbackUsed =
                StringUtils.hasText(requestedModelCode)
                        && StringUtils.hasText(actualModelCode)
                        && !Objects.equals(
                        requestedModelCode,
                        actualModelCode
                );

        ResponseMeta responseMeta = new ResponseMeta(
                workflowCode,
                reportMeta.artifactId(),
                requestedModelCode,
                actualModelCode,
                fallbackUsed,
                0,
                reportMeta.totalCount(),
                reportMeta.successCount(),
                reportMeta.failureCount(),
                reportMeta.skippedCount(),
                false
        );

        return new ReportSchema(
                ResponseDocument.CURRENT_SCHEMA_VERSION,
                stream.getMessageId(),
                runId,
                stream.getConversationId(),
                PresentationMode.REPORT,
                status,
                reportSchema.dataComplete(),
                reportSchema.reportId(),
                reportSchema.reportType(),
                reportSchema.queryType(),
                reportSchema.title(),
                reportSchema.subtitle(),
                reportBlockMapper.map(reportSchema),
                List.of(),
                responseMeta
        );
    }
}
