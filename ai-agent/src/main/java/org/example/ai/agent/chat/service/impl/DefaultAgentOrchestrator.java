package org.example.ai.agent.chat.service.impl;

import lombok.RequiredArgsConstructor;
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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
        try {
            // 1. 校验请求参数。
            validateRequest(request);

            // 2. 推送开始处理事件。
            sendEvent(emitter, "thinking", AgentStreamEvent.of(
                    runId,
                    "THINKING",
                    "已创建 Agent 运行任务，正在判断用户问题类型。",
                    null
            ));

            /*
             * 3. 在调用 RAG 之前，先进行意图路由。
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

            // 4. 推送路由结果，方便前端展示和后端排查。
            sendEvent(emitter, "thinking", AgentStreamEvent.of(
                    runId,
                    "THINKING",
                    "路由结果：" + intentResult.getRouteType() + "，原因：" + intentResult.getReason(),
                    intentResult
            ));

            /*
             * 5. 根据路由结果生成运行计划。
             *
             * RoutePlan 只描述“准备做哪些步骤”，不负责真正执行。
             * 当前阶段可以先把计划返回给前端，方便你确认规划是否合理。
             */
            RoutePlan routePlan = planTemplateRegistry.buildPlan(runId, request, intentResult);

            // 6. 推送运行计划。
            sendEvent(emitter, "plan", AgentStreamEvent.of(
                    runId,
                    "PLAN",
                    "已生成 Agent 运行计划。",
                    routePlan
            ));

            /*
             * 7. 如果信息不足，需要追问用户。
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

                sendDoneEvent(emitter, runId);
                return;
            }

            /*
             * 8. 如果是危险操作，直接拒绝。
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

                sendDoneEvent(emitter, runId);
                return;
            }

            /*
             * 9. 如果不是 RAG_ONLY，当前阶段先不真正执行。
             *
             * 因为：
             * - BUSINESS_QUERY 需要 Capability Registry
             * - MIXED_QUERY 需要 ToolExecutor + RAG + AnswerComposer
             * - STATISTIC_QUERY 需要统计类 Capability
             * - WORKFLOW_ACTION 需要人工确认机制
             *
             * 所以当前阶段先返回“已识别 + 已生成计划”。
             */
            if (intentResult.getRouteType() != RouteType.RAG_ONLY) {
                sendEvent(emitter, "answer", AgentStreamEvent.of(
                        runId,
                        "ANSWER",
                        "已识别为 " + intentResult.getRouteType()
                                + "，并生成了运行计划。下一步需要接入 Capability Registry 和 ToolExecutor 后才能真正执行。",
                        routePlan
                ));

                sendDoneEvent(emitter, runId);
                return;
            }

            /*
             * 10. 只有 RAG_ONLY 才会走到这里。
             *
             * 也就是说：
             * 只有制度、流程、规范、文档说明类问题，
             * 才会调用已有 KnowledgeDocumentQueryService。
             */
            sendEvent(emitter, "thinking", AgentStreamEvent.of(
                    runId,
                    "THINKING",
                    "已确认走企业知识库 RAG 问答，正在检索相关文档。",
                    routePlan
            ));

            // 11. 执行 RAG 问答。
            KnowledgeDocumentQueryResponse ragResponse = executeRagQuery(request);

            // 12. 推送最终回答。
            sendEvent(emitter, "answer", AgentStreamEvent.of(
                    runId,
                    "ANSWER",
                    ragResponse.answer(),
                    routePlan
            ));

            // 13. 推送引用来源。
            sendEvent(emitter, "references", AgentStreamEvent.of(
                    runId,
                    "REFERENCES",
                    "引用来源",
                    ragResponse.references()
            ));

            // 14. 推送结束事件，并关闭 SSE。
            sendDoneEvent(emitter, runId);
        } catch (Exception e) {
            // 15. 发生异常时，尽量通过 SSE 返回错误信息。
            sendQuietly(emitter, "error", AgentStreamEvent.of(
                    runId,
                    "ERROR",
                    e.getMessage(),
                    null
            ));

            // 16. 标记 SSE 异常结束。
            emitter.completeWithError(e);
        }
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
}