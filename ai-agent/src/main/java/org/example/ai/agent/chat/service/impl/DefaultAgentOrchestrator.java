package org.example.ai.agent.chat.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.answer.AnswerComposer;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.entity.AgentStreamEvent;
import org.example.ai.agent.chat.service.AgentOrchestrator;

import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.chat.support.AgentStreamSessionFactory;
import org.example.ai.agent.chat.vo.FactPreviewVO;
import org.example.ai.agent.common.enums.AgentStreamEventType;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

@Service
public class DefaultAgentOrchestrator implements AgentOrchestrator {
    private final AgentStreamSessionFactory streamSessionFactory;
    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService;

    private final Executor agentChatExecutor;

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
            ObjectMapper objectMapper
    ) {
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
    }
    @Override
    public SseEmitter chat(AgentRequest request) {
        // 每次请求生成唯一 runId，后续 Trace / RunOps 可以用它串联整条执行链路。
        String runId = UUID.randomUUID().toString().replace("-", "");
        AgentStreamSession stream = streamSessionFactory.create(runId, request.getStreamVersion());
        /*
         * 使用受控线程池执行Agent任务，
         * 不再使用ForkJoinPool.commonPool。
         */
        agentChatExecutor.execute(() -> doChat(request, stream, runId));
        return stream.getEmitter();
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
            /*
             * 在调用 RAG 之前，先进行意图路由。
             *
             * 这一步很关键：
             * 不能所有问题都直接丢给 RAG。
             *
             * 例如：
             * - “合同审批流程是什么？”          -> RAG_ONLY
             * - “查询 A 项目合同金额”           -> BUSINESS_QUERY
             * - “查询 A 项目回款并分析风险”     -> MIXED_QUERY
             * - “本月合同金额统计一下”          -> STATISTIC_QUERY
             */
            IntentResult intentResult = intentRouter.route(request);
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
                // 追问也使用统一回答协议，保证 v2 前端能收到 delta 和 snapshot。
                stream.publishAnswer(intentResult.getClarifyQuestion());
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
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
                stream.publishAnswer("该操作存在风险，当前版本不支持由 Agent 自动执行。");
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                stream.complete();
                return;
            }
            // 写操作只发送预览，当前阶段绝不进入 ToolExecutor
            if (intentResult.getRouteType() == RouteType.WORKFLOW_ACTION) {
                PendingAction pendingAction =pendingActionService.createPendingAction( runId,request.getUserId(),
                        intentResult.getDynamicCapabilityPlan());
                sendActionPreview(stream, runId, intentResult.getDynamicCapabilityPlan(), pendingAction);
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
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                return;
            }
            // 防御性校验：即使前面的路由发生错误，WRITE 能力也不能直接进入工具执行器
            DynamicCapabilityPlan selectedPlan = intentResult.getDynamicCapabilityPlan();
            if (selectedPlan != null && "WRITE".equalsIgnoreCase(selectedPlan.getSideEffect())) {
                PendingAction pendingAction =pendingActionService.createPendingAction( runId,request.getUserId(),
                        selectedPlan);
                sendActionPreview(stream, runId, selectedPlan, pendingAction);
                runTraceService.markSuccess(runId,System.currentTimeMillis() - startTime);
                stream.complete();
                return;
            }
            // BUSINESS_QUERY / MIXED_QUERY / STATISTIC_QUERY 走工具执行链路。
            executeToolPlan(request, stream, runId, routePlan);
            runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);

        } catch (Exception exception) {
            runTraceService.markFailed(
                    runId,
                    System.currentTimeMillis() - startTime,
                    exception.getMessage()
            );
            // Session 统一发送 ERROR 并关闭连接，避免重复完成同一个 SseEmitter。
            stream.error(exception);
        }
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
        // 5. 如果存在失败步骤，直接返回失败摘要。
        ToolResult failedResult = findFirstFailedResult(toolResults);
        if (failedResult != null) {
            // 失败摘要同样走统一回答协议，且不泄露完整 ToolResult。
            stream.publishAnswer(buildFailedAnswer(failedResult));
            stream.complete();
            return;
        }
        // 基于真实业务数据生成最终回答。
        String finalAnswer = answerComposer.compose(request, routePlan, toolResults);

        // v1 发送完整 answer；v2 发送 start、delta 和最终 snapshot。
        stream.publishAnswer(finalAnswer);
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
    private KnowledgeDocumentQueryResponse executeRagQuery(AgentRequest request) {
        // 构造现有 RAG 服务的请求对象。
        KnowledgeDocumentQueryRequest ragRequest = new KnowledgeDocumentQueryRequest(
                request.getCategoryIds(),
                request.getDocumentIds(),
                request.getUserQuestion(),
                request.getTopK(),
                request.getMinScore()
        );
        // 调用现有企业知识库问答服务。
        return knowledgeDocumentQueryService.query(ragRequest);
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
            RoutePlan routePlan
    ) throws Exception {
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
        KnowledgeDocumentQueryResponse ragResponse = executeRagQuery(request);

        // 3. RAG 回答也使用统一 v1/v2 发布协议。
        stream.publishAnswer(ragResponse.answer());

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
     * 向聊天端发送写操作预览。
     */
    private void sendActionPreview( AgentStreamSession stream,
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
        if (toolResults == null
                || toolResults.isEmpty()) {
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
}
