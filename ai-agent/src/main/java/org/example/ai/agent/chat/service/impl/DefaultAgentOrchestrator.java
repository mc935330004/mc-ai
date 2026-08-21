package org.example.ai.agent.chat.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.answer.AnswerComposer;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.entity.AgentStreamEvent;
import org.example.ai.agent.chat.memory.model.ReportFollowUpDecision;
import org.example.ai.agent.chat.memory.service.ReportFollowUpService;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.example.ai.agent.chat.support.ActiveAgentRunRegistry;
import org.example.ai.agent.chat.vo.ChatTextPayloadVO;
import org.example.ai.agent.common.enums.ReportQueryType;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.vo.ActionFormVO;
import org.example.ai.agent.chat.support.AgentClientDisconnectedException;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.chat.support.AgentStreamSessionFactory;
import org.example.ai.agent.chat.vo.FactPreviewVO;
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
import org.example.ai.agent.common.enums.WorkflowPresentationMode;
import org.example.ai.agent.workflow.answer.presentation.WorkflowAnswerPolicyResolver;
import org.example.ai.agent.workflow.answer.text.WorkflowTextAnswerService;
import org.example.ai.agent.workflow.answer.text.WorkflowTextFactBuilder;
import org.example.ai.agent.workflow.answer.text.WorkflowTextFacts;
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
    /**
     * 查询本次运行实际成功的用户可见模型。
     */
    private final ModelUsageService modelUsageService;
    /**
     *  基于上一轮安全结果快照回答追问。
     */
    private final ResultArtifactAnalysisService resultArtifactAnalysisService;
    private final WorkflowAnswerComposer workflowAnswerComposer;
    /**
     * 解析工作流发布版本中的回答展示策略。
     */
    private final WorkflowAnswerPolicyResolver workflowAnswerPolicyResolver;

    /**
     * 构建普通文字回答的确定性事实。
     */
    private final WorkflowTextFactBuilder workflowTextFactBuilder;
    /**
     * 管理当前进程内正在运行的聊天任务。
     */
    private final ActiveAgentRunRegistry activeAgentRunRegistry;
    /**
     * 执行工作流真实流式文字回答。
     */
    private final WorkflowTextAnswerService workflowTextAnswerService;
    /**
     * 意图路由器。
     *
     * 用于判断用户问题应该走 RAG、业务查询、混合问答还是追问。
     */
    private final IntentRouter intentRouter;
    /**
     * 计划模板注册器。
     *
     * 根据 IntentRouter 的路由结果生成 RoutePlan。
     */
    private final PlanTemplateRegistry planTemplateRegistry;
    /**
     * 工具执行器。
     *
     * 用于真正执行 BUSINESS_TOOL 步骤。
     */
    private final ToolExecutor toolExecutor;
    /**
     * Agent 运行主记录服务。
     *
     * 用于写 ai_run_trace。
     */
    private final RunTraceService runTraceService;
    /**
     * 答案组装器。
     *
     * 用于把 ToolExecutor 返回的业务数据转换成自然语言回答。
     */
    private final AnswerComposer answerComposer;
    /**
     * 保存待用户确认的写操作。
     */
    private final PendingActionService pendingActionService;
    private final ObjectMapper objectMapper;
    /**
     *  负责保存聊天会话和助手回答。
     */
    private final AiChatSessionService aiChatSessionService;
    /**
     *  保存成功业务查询产生的可复用上下文。
     */
    private final ConversationStateRecorder conversationStateRecorder;
    /**
     *  读取上一轮结构化状态并补全当前追问。
     */
    private final ConversationContextResolver conversationContextResolver;
    /**
     *  将工作流结果转换为固定报告结构。
     */
    private final ReportSchemaBuilder reportSchemaBuilder;
    /**
     * 模型判定本次报告是否需要 AI 分析。
     */
    private final WorkflowAnswerAnalysisDecider workflowAnswerAnalysisDecider;
    /**
     * AI 分析速度优化配置。
     */
    private final WorkflowAnswerAnalysisProperties analysisProperties;
    /**
     * 分析超时保护专用线程池，避免占用 agentChatExecutor。
     */
    private final ExecutorService workflowAnswerAnalysisExecutor;
    /**
     * 处理报告后的确定性业务追问。
     */
    private final ReportFollowUpService reportFollowUpService;
    /**
     * 从基础报告生成可信分析输入。
     */
    private final ReportAnalysisInputBuilder reportAnalysisInputBuilder;

    /**
     * 模型失败时生成通用事实分析。
     */
    private final ReportAnalysisFallbackService reportAnalysisFallbackService;

    /**
     * 记录报告分析成功、失败和兜底指标。
     */
    private final AgentMetrics agentMetrics;
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
            AnswerComposer answerComposer,
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
            WorkflowTextAnswerService workflowTextAnswerService,
            ActiveAgentRunRegistry activeAgentRunRegistry,
            @Qualifier("workflowAnswerAnalysisExecutor") ExecutorService workflowAnswerAnalysisExecutor) {
        this.streamSessionFactory = streamSessionFactory;
        this.knowledgeDocumentQueryService = knowledgeDocumentQueryService;
        this.agentChatExecutor = agentChatExecutor;
        this.intentRouter = intentRouter;
        this.planTemplateRegistry = planTemplateRegistry;
        this.toolExecutor = toolExecutor;
        this.runTraceService = runTraceService;
        this.answerComposer = answerComposer;
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
        this.workflowTextAnswerService = workflowTextAnswerService;
        this.activeAgentRunRegistry = activeAgentRunRegistry;

    }
    @Override
    public SseEmitter chat(AgentRequest request) {
        String runId = UUID.randomUUID().toString().replace("-", "");
        AgentStreamSession stream = streamSessionFactory.create(runId, request.getStreamVersion());

        FutureTask<Void> task =new FutureTask<>(() -> {
                    doChat(request, stream, runId);
                    return null;
                });
        activeAgentRunRegistry.register(runId, request.getUserId(), request.getConversationId(), task);
        try {
            agentChatExecutor.execute(task);
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
            // 1. 校验请求参数。
            validateRequest(request);
            // 2. 创建运行主记录。
            runTraceService.startRun(runId, request);
            // 2. 推送开始处理事件。
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
            ensureRunActive();
            request.setContextualQuestion(contextualQuestion);
            /*
             * 多项目指代无法唯一确定时直接追问，
             * 禁止把“这个项目”重新交给普通工作流路由。
             */
            if (StringUtils.hasText(request.getContextClarificationQuestion())) {
                publishAssistantAnswer(request, stream, runId, request.getContextClarificationQuestion());
                stream.complete();
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
                stream.complete();
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
                handleReportFollowUp(
                        request,
                        stream,
                        runId,
                        followUpDecision
                );

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
                    stream.complete();
                    return;
                }
                stream.send("thinking",
                        AgentStreamEvent.of(runId, AgentStreamEventType.THINKING.name(), "正在分析上一轮查询结果。", null)
                );
                executeResultAnalysis(request, stream, runId);
                ensureRunActive();
                runTraceService.markSuccess(runId,System.currentTimeMillis()- startTime);
                return;
            }
            IntentResult intentResult =intentRouter.route(request, runId);
            ensureRunActive();
            //  更新路由类型。
            runTraceService.updateRouteType(runId, intentResult.getRouteType());
            // 推送路由结果，方便前端展示和后端排查。
            stream.send(
                    "thinking",
                    AgentStreamEvent.of(
                            runId,
                            AgentStreamEventType.THINKING.name(),
                            "路由结果：" + intentResult.getRouteType() + "，原因：" + intentResult.getReason(),
                            intentResult
                    )
            );

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

                stream.complete();
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
                stream.complete();
                return;
            }

            if (intentResult.getRouteType() == RouteType.WORKFLOW_QUERY) {

                WorkflowExecutionOutcome outcome = executeWorkflowQuery(request, stream, runId, intentResult );
                ensureRunActive();
                long duration =System.currentTimeMillis() - startTime;

                if (outcome.success()) {
                    /*
                     * 部分成功仍属于一次有效业务查询，
                     * 具体失败项目已经写入批量摘要。
                     */
                    runTraceService.markSuccess(
                            runId,
                            duration
                    );
                } else {
                    runTraceService.markFailed(
                            runId,
                            duration,
                            outcome.errorMessage()
                    );
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
                ensureRunActive();
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
            ensureRunActive();
            runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);

        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - startTime;
            /*
             * 用户主动终止优先于普通异常判断。
             */
            if (isRunCancelled(exception)) {
                /*
                 * 清除中断标志，允许当前线程完成
                 * 状态和部分回答的持久化收尾。
                 */
                Thread.interrupted();
                handleRunCancellation(request, stream, runId, duration);
                return;
            }
            AgentClientDisconnectedException disconnected = findClientDisconnected(exception);
            if (disconnected != null) {
                log.debug("SSE客户端已断开，静默收尾，runId={}", runId);
                runTraceService.markCancelled(runId, duration, "客户端连接已断开");
                return;
            }
            runTraceService.markFailed(runId, duration, exception.getMessage());
            stream.error(exception);

        } finally {
            /*
             * 无论成功、失败、取消还是客户端断开，
             * 都必须移除活动任务。
             */
            activeAgentRunRegistry.remove(runId);
        }
    }

    /**
     * 用户主动终止后的统一收尾。
     */
    private void handleRunCancellation(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            long duration) {

        runTraceService.markCancelled(
                runId,
                duration,
                "用户主动终止"
        );

        if (stream.isCompleted()) {
            return;
        }

        String notice =
                "\n\n回答已由用户终止。";

        try {
            if (stream.hasIncrementalAnswerStarted()) {

                String partial =
                        stream.getFinalMarkdownSnapshot();

                String finalContent =
                        StringUtils.hasText(partial)
                                ? partial + notice
                                : notice.trim();

                stream.appendAnswerDelta(notice);

                /*
                 * 正常回答可能已经在极短时间窗口内完成持久化。
                 * 已保存则更新，未保存则新增。
                 */
                if (stream.isAssistantMessagePersisted()) {
                    aiChatSessionService
                            .updateAssistantReportMessage(
                                    request.getUserId(),
                                    request.getConversationId(),
                                    runId,
                                    finalContent,
                                    request.getModelCode(),
                                    null
                            );
                } else {
                    aiChatSessionService
                            .saveAssistantMessage(
                                    request.getUserId(),
                                    request.getConversationId(),
                                    finalContent,
                                    runId,
                                    request.getModelCode(),
                                    "TEXT",
                                    null
                            );

                    stream.markAssistantMessagePersisted();
                }

                stream.finishAnswer(finalContent);
                return;
            }

            /*
             * 尚未开始增量输出时保存一条明确终止消息。
             */
            publishAssistantAnswer(
                    request,
                    stream,
                    runId,
                    "回答已由用户终止。"
            );

            stream.complete();

        } catch (AgentClientDisconnectedException exception) {
            log.debug(
                    "终止任务时客户端已经断开，runId={}",
                    runId
            );
        } catch (Exception exception) {
            log.warn(
                    "终止任务收尾失败，runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName()
            );

            stream.connectionClosed();
        }
    }

    /**
     * 检查当前线程是否已收到主动终止信号。
     */
    private void ensureRunActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException(
                    "回答已由用户终止"
            );
        }
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
                stream.complete();
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
            stream.complete();
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
     * 基于上一轮结果快照回答追问。
     *
     * 不调用工作流执行器，
     * 不调用ToolExecutor，
     * 不请求PM业务接口。
     */
    private void executeResultAnalysis( AgentRequest request,AgentStreamSession stream,String runId) throws Exception {
        ResultArtifactAnalysisResult result = resultArtifactAnalysisService.analyze(request,runId);
        // 结果统计返回Markdown正文，不携带ReportSchema，必须按普通Markdown展示。
        ChatTextPayloadVO payload = ChatTextPayloadVO.builder()
                .presentationType("MARKDOWN")
                .presentationTitle(result.reportTitle())
                .build();
        publishAssistantAnswer(
                request,
                stream,
                runId,
                result.answer(),
                payload
        );
        stream.complete();
    }

    /**
     * 执行业务工具计划。
     *
     * 这是当前阶段新增的核心逻辑：
     * 根据 RoutePlan 调用 ToolExecutor，真正执行 BUSINESS_TOOL。
     */
    private void executeToolPlan(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            RoutePlan routePlan
    ) throws Exception {
        // 1. 推送工具执行开始事件。
        stream.send(
                "thinking",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.THINKING.name(),
                        "已进入业务能力执行阶段，正在调用 ToolExecutor。",
                        null
                )
        );

        // 2. 构建工具执行上下文。
        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .runId(runId)
                .userId(request.getUserId())
                .userContext(request.getPageContext())
                .authorization(request.getAuthorization())
                .variables(new LinkedHashMap<>())
                .build();

        // 3. 执行完整计划。
        List<ToolResult> toolResults = toolExecutor.executePlan(toolContext, routePlan);
        List<FactPreviewVO> factPreview =buildFactPreview(toolResults);
        // 4. 推送工具执行结果。
        stream.send(
                "tool_result",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.TOOL_RESULT.name(),
                        "业务工具执行完成。",
                        null
                )
        );
            /*
             * 在调用最终回答模型之前先发送核心事实，
             * 用户不需要一直等待AI生成完成。
             */
            stream.send("facts",
                    AgentStreamEvent.of(
                            runId,
                            AgentStreamEventType.FACTS.name(),
                            "已提取核心业务数据。",
                            factPreview
                    )
            );
        ChatTextPayloadVO factPayload =ChatTextPayloadVO.builder()
                        .facts(factPreview)
                        .presentationType("REPORT")
                        .presentationTitle("业务数据分析报告")
                        .build();

        // 5. 如果存在失败步骤，仍保留已提取的事实卡片。
        ToolResult failedResult = findFirstFailedResult(toolResults);
        if (failedResult != null) {
            publishAssistantAnswer(
                    request,
                    stream,
                    runId,
                    buildFailedAnswer(failedResult),
                    factPayload
            );
            stream.complete();
            return;
        }

        //  业务查询最终回答与事实卡片写入同一条 TEXT 消息。
        String finalAnswer = answerComposer.compose(request, routePlan, toolResults);
        publishAssistantAnswer(request, stream, runId, finalAnswer, factPayload);
        //  回答与事实快照保存成功后，再记录本轮能力查询上下文。
        conversationStateRecorder.recordToolResult(
                request,
                routePlan,
                runId,
                toolResults
        );
        stream.complete();
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
     * 执行企业知识库 RAG 问答。
     *
     * 这里把 AgentRequest 转换成你现有 RAG 服务需要的 KnowledgeDocumentQueryRequest。
     */
    private KnowledgeDocumentQueryResponse executeRagQuery(AgentRequest request, String runId) {
        KnowledgeDocumentQueryRequest ragRequest =
                new KnowledgeDocumentQueryRequest(
                        request.getCategoryIds(),
                        request.getDocumentIds(),
                        //  RAG 追问同样使用后端补全后的有效问题。
                        request.getEffectiveQuestion(),
                        request.getTopK(),
                        request.getMinScore()
                );

        ModelCallContext ragContext = ModelCallContext.builder()
                .runId(runId)
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                .callType(ModelCallType.RAG)
                //  只切换 RAG 回答生成模型，不切换向量模型。
                .modelCode(request.getModelCode())
                .callSequence(1)
                .build();

        //  向 RAG 回答层传递最近会话记忆。
        return knowledgeDocumentQueryService.query(
                ragRequest,
                ragContext,
                request.getConversationMemory(),
                request.getKnowledgeAccessPrincipal()
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
     * 执行纯 RAG 问答。
     *
     * 当前项目已经有 KnowledgeDocumentQueryService，
     * 所以 RAG_ONLY 不需要经过 ToolExecutor。
     */
    private void executeRagOnly(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            RoutePlan routePlan ) throws Exception {
        // 1. 推送 RAG 检索提示。
        stream.send(
                "thinking",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.THINKING.name(),
                        "已确认走企业知识库 RAG 问答，正在检索相关文档。",
                        null
                )
        );

        // 2. 调用现有 RAG 服务。
        KnowledgeDocumentQueryResponse ragResponse =executeRagQuery(request, runId);

        ChatTextPayloadVO ragPayload =ChatTextPayloadVO.builder().references(ragResponse.references())
                        .presentationType("MARKDOWN")
                        .build();
        publishAssistantAnswer(
                request,
                stream,
                runId,
                ragResponse.answer(),
                ragPayload
        );
        //  回答保存成功后，将 RAG 设置为当前最新会话主题。
        conversationStateRecorder.recordRagResult(
                request,
                runId
        );
        // 4. 推送引用来源。
        stream.send(
                "references",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType.REFERENCES.name(),
                        "引用来源",
                        ragResponse.references()
                )
        );

        // 5. 结束 SSE。
        stream.complete();
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
        stream.markAssistantMessagePersisted();
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

    /**
     * 构建可安全发送给前端的核心事实预览。
     *
     * 最多返回12个字段，必答字段优先。
     */
    private List<FactPreviewVO> buildFactPreview( List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        return toolResults.stream() .filter(result ->
                        result != null && result.isSuccess() && result.getFacts() != null
                ) .flatMap(result ->
                        result.getFacts().stream())
                .filter(fact -> !fact.isMissing())
                .sorted((left, right) ->
                        Boolean.compare( right.isRequired(),left.isRequired())
                ) .limit(12) .map(fact -> FactPreviewVO.builder()
                        .label(fact.getLabel())
                        .value(fact.getDisplayValue())
                        .group(fact.getDisplayGroup())
                        .required(fact.isRequired())
                        .build()) .toList();
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

        WorkflowExecutionOutcome outcome =workflowExecutionFacade.execute(command);

        stream.send("workflow_result",
                AgentStreamEvent.of(runId, AgentStreamEventType.WORKFLOW_RESULT.name(),
                        outcome.success() ? "工作流执行完成。" : "工作流执行失败。", outcome));
        /*
         * 工作流执行失败时没有可用于生成报告的基础数据，
         * 直接返回真实业务错误，禁止继续准备报告或执行分析兜底。
         */
        if (!outcome.success()) {
            String errorMessage =StringUtils.hasText(outcome.errorMessage())? outcome.errorMessage() : "工作流执行失败";
            log.warn("工作流执行失败，runId={}，workflowCode={}，errorCode={}，errorMessage={}", runId, outcome.workflowCode(), outcome.errorCode(), errorMessage);
            publishAssistantAnswer(request, stream, runId, "查询失败：" + errorMessage);
            stream.complete();
            return outcome;
        }
        /**
         *  SSE v1 继续使用旧版 Markdown 回答。
         */
        if (stream.getProtocolVersion() != 2) {
            WorkflowAnswerComposeResult composeResult =workflowAnswerComposer.compose(request, outcome);
            String reportTitle =StringUtils.hasText(outcome.workflowName())
                            ? outcome.workflowName()
                            : "业务数据分析报告";
            String presentationType =composeResult.reportGenerated()
                            ? "REPORT"
                            : "MARKDOWN";
            ChatTextPayloadVO payload =ChatTextPayloadVO.builder()
                            .workflow(outcome)
                    .reportSchema(reportSchemaBuilder.build(outcome,composeResult.artifactId(),queryType))
                            .presentationType(presentationType)
                            .presentationTitle(reportTitle)
                            .build();
            conversationStateRecorder.recordWorkflowResult(request, plan, outcome, runId, composeResult.artifactId(), null, "REPORT".equals(presentationType)
                            ? "REPORT" : "ANSWER");
            publishAssistantAnswer(request, stream, runId, composeResult.answer(), payload);
            stream.complete();
            return outcome;
        }
        /*
         * SSE v2根据发布版本的presentationMode决定展示方式。
         *
         * 策略解析失败时保持旧报表行为，
         * 禁止因为配置读取异常误切换展示方式。
         */
        WorkflowPresentationMode presentationDecision = WorkflowPresentationMode.REPORT;

        try {
            presentationDecision = workflowAnswerPolicyResolver
                            .resolve(outcome)
                            .decide(request.getEffectiveQuestion());
        } catch (RuntimeException exception) {
            log.warn("工作流回答展示策略解析失败，"
                            + "保持原报表展示，runId={}，"
                            + "workflowCode={}，errorType={}", runId, outcome.workflowCode(), exception.getClass().getSimpleName());
        }
        if (presentationDecision == WorkflowPresentationMode.ANSWER) {
            executeWorkflowTextAnswer(request, stream, runId, plan, outcome);
            return outcome;
        }
        // 基础报告准备失败时仍允许发送降级基础报告。
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
        boolean analysisRequired =baseReportSchema.analysis().requiresExecution();
        ChatTextPayloadVO basePayload =ChatTextPayloadVO.builder()
                        .workflow(outcome)
                        .reportSchema(baseReportSchema)
                        .presentationType("REPORT")
                        .presentationTitle(baseReportSchema.title())
                        .build();
        String baseMessageContent =analysisRequired
                        ? "基础报告已生成，正在进行 AI 分析。"
                        : "业务报告已生成。";
        // 先保存基础报告，保证刷新页面或分析失败时仍然有可恢复内容。
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                baseMessageContent,
                runId,
                request.getModelCode(),
                "TEXT",
                objectMapper.writeValueAsString(basePayload)
        );
        sendReportBaseIfV2(stream,runId,baseReportSchema);
        if (preparation != null) {
            WorkflowTextFacts contextFacts = null;
            try {
                // 上下文事实构建失败不能影响已经生成的基础报告。
                contextFacts = workflowTextFactBuilder.build(preparation);
            } catch (RuntimeException exception) {
                log.warn("报告会话上下文事实构建失败，runId={}，workflowCode={}，errorType={}", runId, outcome.workflowCode(), exception.getClass().getSimpleName(), exception);
            }
            conversationStateRecorder.recordWorkflowResult(request, plan, outcome, runId, preparation.artifactId(), contextFacts, "REPORT");
        }

        /*
         * 触发 AI 分析的两条通道：
         * 1. ANALYSIS_REPORT（用户明确要求分析）——意图强信号，直接异步分析；
         * 2. DATA_QUERY + 判定开启——报告先展示，由模型判定本次数据是否需要分析。
         */
        boolean decisionEnabled = analysisProperties.isDecisionEnabled() && !analysisRequired;
        if (!analysisRequired && !decisionEnabled) {
            completeDataQueryReport(request, stream, runId, baseReportSchema);
            return outcome;
        }
        if (preparation == null) {
            if (analysisRequired) {
                completeReportWithRuleFallback(request, stream, runId, outcome, baseReportSchema,
                        new IllegalStateException("报告基础数据准备失败"), System.currentTimeMillis());
            } else {
                completeDataQueryReport(request, stream, runId, baseReportSchema);
            }
            return outcome;
        }
        /*
         * 当前方法本身已经运行在agentChatExecutor中，
         * 不能再次脱离主聊天任务提交异步任务，
         * 否则用户无法终止后续报表分析。
         */
        analyzeWorkflowReportAsync(request, stream, runId, outcome, preparation, baseReportSchema);
        return outcome;
    }

    /**
     * 执行工作流普通文字回答。
     *
     * 本方法不创建ReportSchema，
     * 也不发送REPORT_BASE、REPORT_ANALYSIS或REPORT_DONE。
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
            stream.complete();
            return;
        }
        WorkflowTextFacts facts = workflowTextFactBuilder.build(preparation);
        /*
         * 在模型调用前保存Artifact上下文。
         * 即使模型失败，下一轮仍可继续基于本次结果追问。
         */
        conversationStateRecorder.recordWorkflowResult(request, plan, outcome, runId, preparation.artifactId(), facts, "ANSWER");
        workflowTextAnswerService.streamAnswer(request, stream, runId, facts);
    }


    /**
     * 完成普通数据查询报告。
     */
    private void completeDataQueryReport(AgentRequest request, AgentStreamSession stream,
                                         String runId, ReportSchemaVO reportSchema) throws Exception {

        stream.send(
                "report_done",
                AgentStreamEvent.builder()
                        .runId(runId)
                        .type("REPORT_DONE")
                        .content("")
                        .data(reportSchema)
                        .presentationType("REPORT")
                        .presentationTitle(reportSchema.title())
                        .build()
        );

        /*
         * 报告先完成展示，再追加独立助手追问。
         */
        publishReportFollowUpPrompt(request, stream, runId);
        stream.complete();
    }

    /**
     *  异步生成 AI 结构化分析。
     */
    private void analyzeWorkflowReportAsync(AgentRequest request, AgentStreamSession stream, String runId,
                                            WorkflowExecutionOutcome outcome, WorkflowAnswerPreparation preparation,
                                            ReportSchemaVO baseReportSchema) throws Exception{
        long analysisStartedAt = System.currentTimeMillis();
        try {
            /*
             * DATA_QUERY 且判定开启时，先由模型判定本次数据是否需要分析。
             * 判定不需要分析时与普通数据查询一致收尾，只展示业务报告。
             */
            if (analysisProperties.isDecisionEnabled() && !baseReportSchema.analysis().requiresExecution()) {
                boolean needAnalysis = workflowAnswerAnalysisDecider.decide(
                        request,
                        runId,
                        preparation
                );
                if (!needAnalysis) {
                    completeDataQueryReport(request, stream, runId, baseReportSchema);
                    return;
                }
            }
            stream.send(
                    "report_analysis_start",
                    AgentStreamEvent.builder()
                            .runId(runId)
                            .type("REPORT_ANALYSIS_START")
                            .content("")
                            .data(Map.of("status", "RUNNING"))
                            .build()
            );

            WorkflowAnswerAnalysisResult result = analyzeReportWithTimeout(request, preparation,baseReportSchema);
            completeAnalyzedReport(request, stream, runId, outcome, baseReportSchema, result.analysis());
            agentMetrics.recordReportAnalysisCompleted("AI", "NONE", System.currentTimeMillis() - analysisStartedAt);
        } catch (Exception exception) {
            if (isRunCancelled(exception)) {
                Thread.interrupted();
                completeCancelledReport(request, stream, runId, baseReportSchema);
                throw new CancellationException("回答已由用户终止");
            }
            completeReportWithRuleFallback(request, stream, runId, outcome, baseReportSchema, exception, analysisStartedAt);
        }
    }

    /**
     * 报表分析取消后保留基础报告。
     */
    private void completeCancelledReport(AgentRequest request, AgentStreamSession stream, String runId,
                                         ReportSchemaVO baseReportSchema) throws Exception {
        /*
         * payloadJson传null，
         * AiChatSessionServiceImpl会保留已有ReportSchema。
         */
        aiChatSessionService.updateAssistantReportMessage(
                        request.getUserId(),
                        request.getConversationId(),
                        runId,
                        "基础报告已生成，AI分析已由用户终止。",
                        request.getModelCode(),
                        null
                );

        stream.markAssistantMessagePersisted();
        stream.send("report_done",
                AgentStreamEvent.builder()
                        .runId(runId)
                        .type("REPORT_DONE")
                        .content("AI分析已由用户终止，基础报告仍然有效。")
                        .data(baseReportSchema)
                        .presentationType("REPORT")
                        .presentationTitle(baseReportSchema.title())
                        .build()
        );
        stream.complete();
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
     * 统一完成AI分析或规则分析结果。
     *
     * 先持久化完整报告，再推送SSE事件，
     * 保证客户端中途断开后刷新页面仍能恢复结果。
     */
    private void completeAnalyzedReport(AgentRequest request, AgentStreamSession stream, String runId, WorkflowExecutionOutcome outcome, ReportSchemaVO baseSchema, ReportSchemaVO.Analysis analysis) throws Exception {
        ReportSchemaVO finalSchema = reportSchemaBuilder.withAnalysis(baseSchema, analysis);
        ChatTextPayloadVO payload = ChatTextPayloadVO.builder()
                .workflow(outcome)
                .reportSchema(finalSchema)
                .presentationType("REPORT")
                .presentationTitle(finalSchema.title())
                .build();
        updateAssistantReportMessage(request, stream, runId, "基础报告和数据分析已生成。", payload);

        stream.send(
                "report_analysis_delta",
                AgentStreamEvent.builder()
                        .runId(runId)
                        .type("REPORT_ANALYSIS_DELTA")
                        .content("")
                        .data(analysis)
                        .build()
        );

        stream.send("report_done", AgentStreamEvent.builder()
                .runId(runId).type("REPORT_DONE").content("").data(finalSchema)
                .presentationType("REPORT").presentationTitle(finalSchema.title())
                .build());
        publishReportFollowUpPrompt(request, stream, runId);
        stream.complete();
    }

    /**
     * 模型、解析或分析准备失败时生成通用事实分析。
     *
     * 普通用户不接收模型异常和解析错误，
     * 真实失败原因只进入日志和监控标签。
     */
    private void completeReportWithRuleFallback(AgentRequest request, AgentStreamSession stream, String runId,
                                                WorkflowExecutionOutcome outcome, ReportSchemaVO baseSchema, Exception exception, long analysisStartedAt) {
        Throwable rootCause = findRootCause(exception);
        /*
         * 客户端断开不属于分析失败，
         * 禁止覆盖已经持久化的成功结果。
         */
        if (rootCause instanceof AgentClientDisconnectedException) {
            log.info("客户端连接已断开，报告分析结果无法推送，runId={}", runId);
            return;
        }
        String fallbackReason = classifyFallbackReason(rootCause);
        log.warn(
                "报告AI分析未完成，使用规则分析兜底，"+ "runId={}，reason={}，errorType={}",
                runId,
                fallbackReason,
                rootCause.getClass().getSimpleName()
        );

        try {
            ReportAnalysisInput input = reportAnalysisInputBuilder.build(baseSchema);
            ReportSchemaVO.Analysis fallbackAnalysis = reportAnalysisFallbackService.build(input);
            completeAnalyzedReport(request, stream, runId, outcome, baseSchema, fallbackAnalysis);
            agentMetrics.recordReportAnalysisCompleted("RULE_FALLBACK", fallbackReason, System.currentTimeMillis() - analysisStartedAt);
        } catch (Exception fallbackException) {
            Throwable fallbackRootCause = findRootCause(fallbackException);
            if (fallbackRootCause instanceof AgentClientDisconnectedException) {
                log.info("客户端连接已断开，规则分析结果无法推送，runId={}", runId);
                return;
            }
            log.error("报告规则分析兜底异常，runId={}，errorType={}", runId, fallbackRootCause.getClass().getSimpleName());
            stream.error(fallbackException);
        }
    }

    /**
     * 将内部异常转换成低基数监控分类。
     *
     * 禁止把异常原文直接作为指标标签。
     */
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
     *  仅向 SSE v2 客户端发送结构化基础报告。
     */
    private void sendReportBaseIfV2(AgentStreamSession stream,String runId, ReportSchemaVO reportSchema) throws Exception {
        if (stream.getProtocolVersion() != 2) {
            return;
        }
        stream.send("report_base",
                AgentStreamEvent.builder()
                        .runId(runId)
                        .type("REPORT_BASE")
                        .content("")
                        .data(reportSchema)
                        .presentationType("REPORT")
                        .presentationTitle(reportSchema.title())
                        .build()
        );
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
     *  最终 Markdown 与结构化展示快照必须在同一条 TEXT 消息中保存。
     */
    private void publishAssistantAnswer(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            String answer,
            ChatTextPayloadVO payload) throws Exception {
        //  所有回答在保存和发送前统一清理用户不需要的内部信息。
        String visibleAnswer = sanitizeUserVisibleAnswer(answer);
        // 安全过滤不能让最终回答变成空字符串，避免前端显示“未返回回答内容”。
        if (!StringUtils.hasText(visibleAnswer)) {
            visibleAnswer ="本次分析没有生成可展示内容，请明确统计字段和统计方式后重新提问。";
        }
        String effectiveModelCode =
                resolveEffectiveModelCode(request, runId);
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                visibleAnswer,
                runId,
                effectiveModelCode,
                "TEXT",
                payload == null
                        ? null
                        : objectMapper.writeValueAsString(payload)
        );
        stream.markAssistantMessagePersisted();
        /*
         * 在完成事件中同时返回首选模型和实际成功模型。
         */
        stream.setAnswerModelResult(
                request.getModelCode(),
                effectiveModelCode
        );

        stream.publishAnswer(
                visibleAnswer,
                payload == null
                        ? "MARKDOWN"
                        : payload.getPresentationType(),
                payload == null
                        ? null
                        : payload.getPresentationTitle()
        );
    }
    /**
     * 更新已保存的报告消息，并向当前连接推送最终回答。
     */
    private void updateAssistantReportMessage(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            String answer,
            ChatTextPayloadVO payload) throws Exception {
        String visibleAnswer = sanitizeUserVisibleAnswer(answer);
        String effectiveModelCode =
                resolveEffectiveModelCode(request, runId);

        aiChatSessionService.updateAssistantReportMessage(
                request.getUserId(),
                request.getConversationId(),
                runId,
                visibleAnswer,
                effectiveModelCode,
                payload == null
                        ? null
                        : objectMapper.writeValueAsString(payload)
        );
        stream.markAssistantMessagePersisted();
        stream.setAnswerModelResult(request.getModelCode(), effectiveModelCode);
        stream.publishAnswer(visibleAnswer, payload == null
                        ? "MARKDOWN"
                        : payload.getPresentationType(), payload == null
                        ? null
                        : payload.getPresentationTitle()
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
     *  普通文本回答不携带额外结构化展示数据。
     */
    private void publishAssistantAnswer(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            String answer) throws Exception {

        publishAssistantAnswer(request, stream, runId, answer, null);
    }
}
