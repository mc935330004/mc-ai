package org.example.ai.agent.workflow.answer.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.support.AgentClientDisconnectedException;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.CancellationException;

/**
 * 工作流文字回答服务。
 *
 * 业务事实立即展示，
 * AI只负责补充解释和建议。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTextAnswerService {

    private static final String SYSTEM_PROMPT = """
            你是企业PM项目管理系统的业务问答助手。

            回答规则：
            1. 只能使用用户问题和“可信事实JSON”回答。
            2. 可信事实中的金额、数量和完整性已经由后端确定，不得重新计算或修改。
            3. 不得补充JSON中不存在的项目、金额、状态、风险或业务结论。
            4. 当前没有风险规则结果时，不得自行判断项目存在风险或不规范。
            5. 重点解释核心数据、金额变化和用户真正关心的问题。
            6. 多项目查询只给汇总和简短说明，不展开全部项目列表。
            7. 使用简洁中文。
            8. 只允许普通段落、粗体和一级无序列表。
            9. 禁止Markdown表格、HTML、CSS、代码块和嵌套列表。
            10. 不要重复抄写后端已经展示的全部事实。
            """;

    private static final String MODEL_UNAVAILABLE_TEXT =
            "智能分析暂时不可用，以上业务数据和确定性事实仍然有效。";

    private static final String CANCELLED_TEXT =
            "回答已由用户终止。";

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;
    private final AiChatSessionService aiChatSessionService;

    /**
     * 发送工作流文字回答。
     */
    public void streamAnswer(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            String workflowCode,
            String artifactId,
            WorkflowTextFacts facts) throws Exception {

        if (facts == null
                || !StringUtils.hasText(
                facts.deterministicMarkdown())) {

            throw new IllegalArgumentException(
                    "工作流确定性事实不能为空"
            );
        }

        /*
         * 业务事实不等待模型，
         * 查询完成后立即作为完整区块展示。
         */
        stream.startChatResponse();

        stream.publishResponseBlock(
                new TextBlock(
                        "business_facts",
                        "查询结果",
                        0,
                        BlockStatus.READY,
                        BlockSource.BUSINESS,
                        facts.deterministicMarkdown()
                )
        );

        /*
         * AI分析使用独立TEXT区块进行真实流式输出。
         */
        stream.startTextResponse(
                "ai_analysis",
                "智能分析",
                10,
                BlockSource.AI
        );

        StringBuilder modelAnswer =
                new StringBuilder();

        ModelCallContext context =
                ModelCallContext.builder()
                        .runId(runId)
                        .conversationId(
                                request.getConversationId()
                        )
                        .userId(request.getUserId())
                        .modelCode(request.getModelCode())
                        .callType(ModelCallType.ANSWER)
                        .build();

        String userPrompt =
                buildUserPrompt(request, facts);

        try {
            for (ChatResponse response
                    : chatClientService
                    .stream(
                            context,
                            SYSTEM_PROMPT,
                            userPrompt
                    )
                    .toIterable()) {

                String delta =
                        extractDelta(
                                response,
                                modelAnswer
                        );

                if (!StringUtils.hasText(delta)) {
                    continue;
                }

                modelAnswer.append(delta);
                stream.appendTextResponse(delta);
            }

            if (!StringUtils.hasText(modelAnswer)) {
                modelAnswer.append(MODEL_UNAVAILABLE_TEXT);
                stream.appendTextResponse(MODEL_UNAVAILABLE_TEXT);
            }

        } catch (RuntimeException exception) {
            AgentClientDisconnectedException disconnected =
                    findClientDisconnected(exception);

            if (disconnected != null) {
                throw disconnected;
            }

            if (isRunCancelled(exception)) {
                finishCancelledAnswer(
                        request,
                        stream,
                        runId,
                        workflowCode,
                        artifactId,
                        facts,
                        modelAnswer
                );

                CancellationException cancelled =
                        new CancellationException(CANCELLED_TEXT);

                cancelled.initCause(exception);
                throw cancelled;
            }

            log.warn(
                    "工作流文字分析失败，保留确定性事实，runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName()
            );

            // 模型已经输出部分内容时，增加段落间隔，避免降级提示与正文粘连
            String unavailableNotice =
                    StringUtils.hasText(modelAnswer)
                            ? "\n\n" + MODEL_UNAVAILABLE_TEXT
                            : MODEL_UNAVAILABLE_TEXT;

            modelAnswer.append(unavailableNotice);
            stream.appendTextResponse(unavailableNotice);
        }

        String analysisText = modelAnswer.toString().trim();

        stream.finishTextResponse(analysisText);
        stream.setResponseDataComplete(facts.dataComplete());
        stream.setResponseMeta(buildResponseMeta(workflowCode, artifactId, request.getModelCode()));
        AiResponse finalResponse = stream.getChatResponseAccumulator().complete();
        String finalAnswer = buildStoredAnswer(facts.deterministicMarkdown(), analysisText);

        /*
         * 先保存完整Block响应，
         * 再发送RESPONSE_DONE。
         */
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                finalAnswer,
                runId,
                request.getModelCode(),
                "TEXT",
                objectMapper.writeValueAsString(
                        finalResponse
                )
        );
        stream.finishChatResponse();
    }

    /**
     * 用户终止模型流时保存当前部分结果。
     */
    private void finishCancelledAnswer(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            String workflowCode,
            String artifactId,
            WorkflowTextFacts facts,
            StringBuilder modelAnswer) throws Exception {

        String analysisText =
                StringUtils.hasText(modelAnswer)
                        ? modelAnswer.toString().trim()
                        + "\n\n"
                        + CANCELLED_TEXT
                        : CANCELLED_TEXT;

        stream.finishTextResponse(analysisText);
        stream.setResponseDataComplete(
                facts.dataComplete()
        );

        stream.setResponseMeta(
                buildResponseMeta(
                        workflowCode,
                        artifactId,
                        request.getModelCode()
                )
        );

        AiResponse cancelledResponse =
                stream.getChatResponseAccumulator()
                        .cancel();

        String finalAnswer =
                buildStoredAnswer(
                        facts.deterministicMarkdown(),
                        analysisText
                );

        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                finalAnswer,
                runId,
                request.getModelCode(),
                "TEXT",
                objectMapper.writeValueAsString(
                        cancelledResponse
                )
        );
        stream.cancelChatResponse();
    }

    /**
     * 创建回答运行信息。
     */
    private ResponseMeta buildResponseMeta(
            String workflowCode,
            String artifactId,
            String modelCode) {

        return new ResponseMeta(
                workflowCode,
                artifactId,
                modelCode,
                modelCode,
                false,
                0,
                0,
                0,
                0,
                0,
                false
        );
    }

    /**
     * 创建数据库中的纯文字内容。
     *
     * content继续用于会话记忆，
     * payloadJson用于恢复完整Block。
     */
    private String buildStoredAnswer(
            String businessFacts,
            String analysisText) {

        if (!StringUtils.hasText(analysisText)) {
            return businessFacts.trim();
        }

        return (
                businessFacts.trim()
                        + "\n\n"
                        + analysisText.trim()
        ).trim();
    }

    /**
     * 构造精简模型输入。
     */
    private String buildUserPrompt(
            AgentRequest request,
            WorkflowTextFacts facts) throws Exception {

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
     * 兼容模型返回增量文本或累计文本。
     */
    private String extractDelta(
            ChatResponse response,
            StringBuilder accumulatedModelAnswer) {

        if (response == null
                || response.getResult() == null
                || response.getResult()
                .getOutput() == null) {

            return "";
        }

        String current =
                response.getResult()
                        .getOutput()
                        .getText();

        if (!StringUtils.hasText(current)) {
            return "";
        }

        String previous =
                accumulatedModelAnswer.toString();

        if (StringUtils.hasText(previous)
                && current.startsWith(previous)) {

            return current.substring(
                    previous.length()
            );
        }

        return current;
    }

    /**
     * 从异常包装中查找客户端断开异常。
     */
    private AgentClientDisconnectedException
    findClientDisconnected(Throwable throwable) {

        Throwable current = throwable;

        while (current != null) {
            if (current
                    instanceof AgentClientDisconnectedException disconnected) {

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
     * 判断模型流是否被用户主动终止。
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