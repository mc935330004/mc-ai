package org.example.ai.agent.answer.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.answer.planner.ResponsePlan;
import org.example.ai.agent.answer.planner.ResponsePlanner;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.protocol.stream.BlockErrorPayload;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.support.AgentClientDisconnectedException;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.example.ai.agent.chat.protocol.stream.ResponseSnapshotPayload;
import org.example.ai.agent.chat.protocol.stream.ResponseStreamEvent;

import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * 统一业务文字回答服务。
 *
 * 普通能力和工作流共用本服务。
 * 确定性业务区块立即返回，AI只补充自然语言说明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessTextAnswerService {

    private static final String SYSTEM_PROMPT = """
        你是企业PM项目管理系统的业务问答助手。

        回答规则：
        1. 只能使用用户问题和“可信事实JSON”回答。
        2. 可信事实已经通过模型权限和用户展示权限过滤。
        3. 金额、数量和完整性已经由后端确定，不得重新计算或修改。
        4. 不得补充JSON中不存在的项目、金额、状态、风险或业务结论。
        5. 没有风险规则命中结果时，不得自行认定项目存在风险或不规范。
        6. 重点解释核心数据、金额变化和用户真正关心的问题。
        7. 多项目查询只给汇总和简短说明，不展开全部项目列表。
        8. 使用简洁中文。
        9. 只允许普通段落、粗体和一级无序列表。
        10. 禁止Markdown表格、HTML、CSS、代码块和嵌套列表。
        11. 不要重复抄写已经由业务区块展示的全部事实。
        12. modelFactsTruncated=true表示模型只收到部分允许分析的事实。
            此时只能解释已提供的事实，不得声称已分析全部指标，
            不得根据这部分事实推断全量最大值、最小值、合计或整体风险。
        13. 模型输入被裁剪不等于业务查询失败或页面数据缺失。
            需要说明分析范围时，用一句简短说明：
            本段分析仅依据本次提供的部分事实。
            不得声称页面必然缺少其他指标。
        14. modelAvailableFactCount和modelIncludedFactCount是事实条目数量，
            不是项目数量、合同数量或业务记录数量。
        15. dataComplete=false时，只能依据已返回数据说明，不得宣称查询结果完整。
        """;

    private static final String MODEL_UNAVAILABLE_TEXT =
            "智能分析暂时不可用，已保存的业务数据和查询快照仍然保留。";

    private static final String CANCELLED_TEXT =
            "回答已由用户终止。";

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;
    private final AiChatSessionService aiChatSessionService;
    private final ResponsePlanner responsePlanner;

    /**
     * 输出统一CHAT回答。
     * narrativeOnly=true时只输出分析文字，不重复展示已有业务区块。
     */
    public void streamAnswer(AgentRequest request, AgentStreamSession stream, String runId, String workflowCode,
                             String artifactId, BusinessTextFacts facts, boolean narrativeOnly) throws Exception {

        stream.checkCancellation();
        if (facts == null) {
            throw new IllegalArgumentException("统一业务事实不能为空");
        }
        ResponsePlan responsePlan;
        if (narrativeOnly) {
            // 定性追问只生成文字，完整性仍受原数据和未确认对象约束。
            responsePlan = new ResponsePlan(
                    List.of(),
                    true,
                    facts.factSet().dataComplete()
                            && facts.unknownObjectIds().isEmpty(),
                    facts.factSet().totalCount()
            );
        } else {
            responsePlan = responsePlanner.plan(
                    request.getEffectiveQuestion(),
                    facts.factSet(),
                    facts.displayObjectIds(),
                    facts.unknownObjectIds()
            );
        }

        stream.setResponseDataComplete(responsePlan.dataComplete());
        stream.setResponseMeta(
                buildResponseMeta(workflowCode, artifactId, request.getModelCode())
        );
        stream.startChatResponse();

        // 确定性业务结果先展示，不等待模型。
        for (ResponseBlock block : responsePlan.blocks()) {
            stream.publishResponseBlock(block);
        }

        if (!responsePlan.narrativeRequired()
                && !request.isResultAnalysisRequest()) {
            finishAnswer(request, stream, runId, responsePlan, "");
            return;
        }

        stream.startTextResponse("ai_analysis", "智能分析", 100, BlockSource.AI);

        // 模型开始前保存一次，刷新时可恢复业务区块和分析占位。
        saveRunningSnapshot(request, stream, runId, responsePlan);

        StringBuilder modelAnswer = new StringBuilder();
        ModelCallContext context = ModelCallContext.builder()
                .runId(runId)
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                .modelCode(request.getModelCode())
                .callType(ModelCallType.ANSWER)
                .build();

        String userPrompt = buildUserPrompt(request, facts);

        try {
            for (ChatResponse response : chatClientService.stream(context, SYSTEM_PROMPT, userPrompt).toIterable()) {
                stream.checkCancellation();
                String delta = extractDelta(response);
                // 空字符串不发送，空格和换行原样保留。
                if (delta.isEmpty()) {
                    continue;
                }
                modelAnswer.append(delta);
                stream.appendTextResponse(delta);
            }
         // 模型流结束时再次检查，已接受的取消必须先进入取消收尾。
            stream.checkCancellation();
        } catch (RuntimeException exception) {
            AgentClientDisconnectedException disconnected =findClientDisconnected(exception);
            if (disconnected != null) {
                throw disconnected;
            }

            if (stream.isCancellationRequested() || isRunCancelled(exception)) {
                finishCancelledAnswer(request, stream, runId, responsePlan, modelAnswer);
                CancellationException cancelled = new CancellationException(CANCELLED_TEXT);
                cancelled.initCause(exception);
                throw cancelled;
            }
            log.warn(
                    "业务文字分析失败，保留已有结果，runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName()
            );
            failNarrativeBlock(stream, modelAnswer);
            finishAnswer(request, stream, runId, responsePlan, modelAnswer.toString());
            return;
        }

        if (!StringUtils.hasText(modelAnswer)) {
            failNarrativeBlock(stream, modelAnswer);
            finishAnswer(request, stream, runId, responsePlan, modelAnswer.toString());
            return;
        }

        String analysisText = modelAnswer.toString();
        stream.finishTextResponse(analysisText);
        finishAnswer(request, stream, runId, responsePlan, analysisText);
    }

    /**
     * 在模型开始前保存RUNNING快照，不在逐字输出时写数据库。
     * 保存与发送使用同一份JSON，避免序号和校验值不一致。
     */
    private void saveRunningSnapshot(AgentRequest request, AgentStreamSession stream, String runId, ResponsePlan responsePlan) throws Exception {

        synchronized (stream) {
            stream.checkCancellation();
            ResponseStreamEvent<ResponseSnapshotPayload> event =
                    stream.getResponseEventFactory().responseSnapshot(
                            stream.getChatResponseAccumulator().snapshot()
                    );

            aiChatSessionService.saveAssistantMessage(
                    request.getUserId(),
                    request.getConversationId(),
                    buildStoredAnswer(
                            responsePlan,
                            "",
                            request.isResultAnalysisRequest()
                    ),
                    runId,
                    request.getModelCode(),
                    "TEXT",
                    event.payload().documentJson()
            );
            // 保存成功后发送同一个事件，不额外创建第二份快照。
            stream.sendResponseEvent(event);
        }
    }

    /**
     * 标记AI说明生成失败。
     *
     * 已经发送的业务区块继续保留。
     */
    private void failNarrativeBlock(AgentStreamSession stream, StringBuilder modelAnswer) throws Exception {

        String unavailableNotice = StringUtils.hasText(modelAnswer)
                        ? "\n\n" + MODEL_UNAVAILABLE_TEXT
                        : MODEL_UNAVAILABLE_TEXT;

        modelAnswer.append(unavailableNotice);
        stream.appendTextResponse(unavailableNotice);

        stream.failResponseBlock(
                new BlockErrorPayload(
                        "ai_analysis",
                        BlockType.TEXT,
                        "MODEL_NARRATIVE_FAILED",
                        MODEL_UNAVAILABLE_TEXT,
                        true
                )
        );
    }

    /**
     * 保存最终文字回答，保存开始后不再接受新的取消请求。
     */
    private void finishAnswer(AgentRequest request, AgentStreamSession stream, String runId,
                              ResponsePlan responsePlan, String analysisText) throws Exception {
        stream.beginFinalization();
        AiResponse finalResponse = stream.getChatResponseAccumulator().complete();
        String storedAnswer = buildStoredAnswer(responsePlan, analysisText, request.isResultAnalysisRequest());
        aiChatSessionService.saveAssistantMessage(request.getUserId(), request.getConversationId(), storedAnswer, runId, request.getModelCode(), "TEXT", objectMapper.writeValueAsString(finalResponse));
        stream.finishChatResponse();
    }

    /**
     * 用户取消时保存已有业务区块和分析文字，保存成功后再通知前端。
     */
    private void finishCancelledAnswer(AgentRequest request, AgentStreamSession stream, String runId, ResponsePlan responsePlan, StringBuilder modelAnswer) throws Exception {
        // 清除中断标志，允许当前线程完成数据库收尾。
        Thread.interrupted();
        String analysisText = modelAnswer.toString();
        AiResponse cancelledResponse = stream.prepareCancelledChatResponse(analysisText);
        String storedAnalysis = analysisText.isBlank()
                ? CANCELLED_TEXT
                : analysisText + "\n\n" + CANCELLED_TEXT;
        String storedAnswer = buildStoredAnswer(responsePlan, storedAnalysis, request.isResultAnalysisRequest());
        aiChatSessionService.saveAssistantMessage(request.getUserId(), request.getConversationId(), storedAnswer, runId, request.getModelCode(), "TEXT", objectMapper.writeValueAsString(cancelledResponse));
        stream.cancelChatResponse();
    }

    /**
     * 创建回答运行信息。
     */
    private ResponseMeta buildResponseMeta(String workflowCode, String artifactId, String modelCode) {
        return new ResponseMeta(workflowCode, artifactId, modelCode, modelCode, false,
                0, 0, 0, 0, 0, false);
    }

    /**
     * 保存会话摘要。
     *
     * 完整业务区块仍保存在payloadJson中。
     */
    private String buildStoredAnswer(ResponsePlan responsePlan, String analysisText, boolean resultAnalysis) {
        StringBuilder summary = new StringBuilder();
        if (resultAnalysis) {
            summary.append("本轮基于上一轮查询结果进行分析。");
        } else if (responsePlan.totalCount() <= 0) {
            summary.append("没有查询到符合条件的业务数据。");
        } else {
            summary.append("查询完成，共获得 ")
                    .append(responsePlan.totalCount())
                    .append(" 条业务数据。");
        }
        if (!responsePlan.dataComplete()) {
            summary.append(" 部分数据未完整返回。");
        }
        if (StringUtils.hasText(analysisText)) {
            summary.append("\n\n").append(analysisText.trim());
        }
        return summary.toString();
    }

    /**
     * 构造允许发送给模型的精简输入。
     */
    private String buildUserPrompt(
            AgentRequest request,
            BusinessTextFacts facts) throws Exception {

        return """
                用户本轮问题：
                %s

                后端可信事实JSON：
                %s

                请基于以上事实补充简洁说明。
                如果事实不足，请明确说明信息不足，不要猜测。
                """
                .formatted(
                        request.getEffectiveQuestion(),
                        objectMapper.writeValueAsString(
                                facts.safeModelInput()
                        )
                );
    }

    /**
     * 读取当前模型片段，不猜测累计文本，不删除重复内容或空白。
     */
    private String extractDelta(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String delta = response.getResult().getOutput().getText();
        return delta == null ? "" : delta;
    }

    /**
     * 从异常包装中查找客户端断开异常。
     */
    private AgentClientDisconnectedException findClientDisconnected(
            Throwable throwable) {

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
     * 判断模型调用是否被用户终止。
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
}