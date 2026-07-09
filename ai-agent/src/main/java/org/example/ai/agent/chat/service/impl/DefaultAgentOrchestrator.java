package org.example.ai.agent.chat.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.AnswerComposer;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.entity.AgentStreamEvent;
import org.example.ai.agent.chat.service.AgentOrchestrator;

import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryRequest;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryResponse;
import org.example.ai.agent.modules.knowledgebase.service.impl.KnowledgeDocumentQueryService;
import org.example.ai.agent.plan.PlanTemplateRegistry;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.router.IntentResult;
import org.example.ai.agent.router.IntentRouter;
import org.example.ai.agent.router.RouteType;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolExecutor;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.trace.service.RunTraceService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class DefaultAgentOrchestrator implements AgentOrchestrator {

    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService;

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
     * AI 能力定义服务。
     *
     * 用于在聊天运行时读取当前可用业务能力。
     */
    private final CapabilityDefinitionService capabilityDefinitionService;

    @Override
    public SseEmitter chat(AgentRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        // 每次请求生成唯一 runId，后续 Trace / RunOps 可以用它串联整条执行链路。
        String runId = UUID.randomUUID().toString().replace("-", "");
        // 异步执行，避免阻塞 Controller 请求线程。
        CompletableFuture.runAsync(() -> doChat(request, emitter, runId));
        return emitter;
    }
    /**
     * 真正执行 Agent 聊天逻辑。
     *
     * 拆成单独方法是为了让 chat() 方法更清晰。
     */
    private void doChat(AgentRequest request, SseEmitter emitter, String runId) {
        long startTime = System.currentTimeMillis();
        try {
            // 1. 校验请求参数。
            validateRequest(request);
            // 加载当前启用的业务能力清单，后续用于路由和规划。
            String capabilitiesPrompt = capabilityDefinitionService.buildEnabledCapabilitiesPrompt();
            // 2. 创建运行主记录。
            runTraceService.startRun(runId, request);
            // 2. 推送开始处理事件。
            sendEvent(emitter, "thinking", AgentStreamEvent.of(
                    runId,
                    "THINKING",
                    "已创建 Agent 运行任务，正在判断用户问题类型。",
                    capabilitiesPrompt
            ));
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
            sendEvent(emitter, "thinking", AgentStreamEvent.of(
                    runId,
                    "THINKING",
                    "路由结果：" + intentResult.getRouteType() + "，原因：" + intentResult.getReason(),
                    intentResult
            ));

            /*
             *  根据路由结果生成运行计划。
             *
             * RoutePlan 只描述“准备做哪些步骤”，不负责真正执行。
             * 当前阶段可以先把计划返回给前端，方便你确认规划是否合理。
             */
            RoutePlan routePlan = planTemplateRegistry.buildPlan(runId, request, intentResult);

            //  推送运行计划。
            sendEvent(emitter, "plan", AgentStreamEvent.of(
                    runId,
                    "PLAN",
                    "已生成 Agent 运行计划。",
                    routePlan
            ));

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
                sendEvent(emitter, "answer", AgentStreamEvent.of(
                        runId,
                        "ANSWER",
                        intentResult.getClarifyQuestion(),
                        routePlan
                ));
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                sendDoneEvent(emitter, runId);
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
                sendEvent(emitter, "answer", AgentStreamEvent.of(
                        runId,
                        "ANSWER",
                        "该操作存在风险，当前版本不支持由 Agent 自动执行。",
                        routePlan
                ));
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                sendDoneEvent(emitter, runId);
                return;
            }

            /*
             * RAG_ONLY 走企业知识库问答。
             * 其它业务查询类型交给 ToolExecutor 执行真实业务能力。
             */
            if (intentResult.getRouteType() == RouteType.RAG_ONLY) {
                executeRagOnly(request, emitter, runId, routePlan);
                runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);
                return;
            }

            // BUSINESS_QUERY / MIXED_QUERY / STATISTIC_QUERY 走工具执行链路。
            executeToolPlan(request, emitter, runId, routePlan);
            runTraceService.markSuccess(runId, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            runTraceService.markFailed(runId, System.currentTimeMillis() - startTime, e.getMessage() );
            //发生异常时，尽量通过 SSE 返回错误信息。
            sendQuietly(emitter, "error", AgentStreamEvent.of(
                    runId,
                    "ERROR",
                    e.getMessage(),
                    null
            ));
            //标记 SSE 异常结束。
            emitter.completeWithError(e);
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
            SseEmitter emitter,
            String runId,
            RoutePlan routePlan
    ) throws Exception {
        // 1. 推送工具执行开始事件。
        sendEvent(emitter, "thinking", AgentStreamEvent.of(
                runId,
                "THINKING",
                "已进入业务能力执行阶段，正在调用 ToolExecutor。",
                routePlan
        ));

        // 2. 构建工具执行上下文。
        ToolExecutionContext toolContext = ToolExecutionContext.builder()
                .runId(runId)
                .userId(request.getUserId())
                .userContext(request.getPageContext())
                .variables(new LinkedHashMap<>())
                .build();

        // 3. 执行完整计划。
        List<ToolResult> toolResults = toolExecutor.executePlan(toolContext, routePlan);

        // 4. 推送工具执行结果。
        sendEvent(emitter, "tool_result", AgentStreamEvent.of(
                runId,
                "TOOL_RESULT",
                "业务工具执行完成。",
                toolResults
        ));

        // 5. 如果存在失败步骤，直接返回失败摘要。
        ToolResult failedResult = findFirstFailedResult(toolResults);
        if (failedResult != null) {
            sendEvent(emitter, "answer", AgentStreamEvent.of(
                    runId,
                    "ANSWER",
                    buildFailedAnswer(failedResult),
                    toolResults
            ));

            sendDoneEvent(emitter, runId);
            return;
        }
        // 基于真实业务数据生成最终回答。
        String finalAnswer = answerComposer.compose(request, routePlan, toolResults);

        sendEvent(emitter, "answer", AgentStreamEvent.of(
                runId,
                "ANSWER",
                finalAnswer,
                toolResults
        ));

        // 7. 结束 SSE。
        sendDoneEvent(emitter, runId);
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
     * 发送事件
     *
     * @param emitter   SseEmitter
     * @param eventName 事件名称
     * @param event     事件数据
     * @throws Exception 异常
     */
    private void sendEvent(SseEmitter emitter, String eventName, AgentStreamEvent event) throws Exception {
        emitter.send(SseEmitter.event().name(eventName).data(event));
    }

    /**
     * 安静发送事件
     *
     * @param emitter   SseEmitter
     * @param eventName 事件名称
     * @param event     事件数据
     */
    private void sendQuietly(SseEmitter emitter, String eventName, AgentStreamEvent event) {
        try {
            sendEvent(emitter, eventName, event);
        } catch (Exception ignored) {
        }
    }
    /**
     * 发送结束事件，并关闭 SSE。
     */
    private void sendDoneEvent(SseEmitter emitter, String runId) throws Exception {
        sendEvent(emitter, "done", AgentStreamEvent.of(
                runId,
                "DONE",
                "[DONE]",
                null
        ));
        emitter.complete();
    }

    /**
     * 执行纯 RAG 问答。
     *
     * 当前项目已经有 KnowledgeDocumentQueryService，
     * 所以 RAG_ONLY 不需要经过 ToolExecutor。
     */
    private void executeRagOnly(
            AgentRequest request,
            SseEmitter emitter,
            String runId,
            RoutePlan routePlan
    ) throws Exception {
        // 1. 推送 RAG 检索提示。
        sendEvent(emitter, "thinking", AgentStreamEvent.of(
                runId,
                "THINKING",
                "已确认走企业知识库 RAG 问答，正在检索相关文档。",
                routePlan
        ));

        // 2. 调用现有 RAG 服务。
        KnowledgeDocumentQueryResponse ragResponse = executeRagQuery(request);

        // 3. 推送最终回答。
        sendEvent(emitter, "answer", AgentStreamEvent.of(
                runId,
                "ANSWER",
                ragResponse.answer(),
                routePlan
        ));

        // 4. 推送引用来源。
        sendEvent(emitter, "references", AgentStreamEvent.of(
                runId,
                "REFERENCES",
                "引用来源",
                ragResponse.references()
        ));

        // 5. 结束 SSE。
        sendDoneEvent(emitter, runId);
    }
}
