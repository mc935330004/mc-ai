package org.example.ai.agent.workflow.answer.text;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.support.AgentClientDisconnectedException;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.concurrent.CancellationException;
/**
 * 使用真正的模型Flux生成工作流文字回答。
 *
 * 后端确定性事实先展示，
 * 模型只负责解释和建议。
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
            "\n\n智能分析暂时不可用，"
                    + "以上业务数据和确定性事实仍然有效。";

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;
    private final AiChatSessionService aiChatSessionService;

    /**
     * 发送文字形式的工作流回答。
     */
    public void streamAnswer(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            WorkflowTextFacts facts) throws Exception {

        if (facts == null
                || !StringUtils.hasText(
                        facts.deterministicMarkdown()
                )) {
            throw new IllegalArgumentException(
                    "工作流确定性事实不能为空"
            );
        }

        StringBuilder completeAnswer =
                new StringBuilder(
                        facts.deterministicMarkdown()
                );

        StringBuilder modelAnswer =
                new StringBuilder();

        stream.startAnswer("MARKDOWN");

        /*
         * 业务事实优先展示。
         * 即使模型连接失败，用户也不会长时间看到空白页面。
         */
        stream.appendAnswerDelta(
                facts.deterministicMarkdown()
        );

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

        boolean modelContentReceived = false;

        try {
            /*
             * 使用toIterable逐块消费Flux。
             * 每个ChatResponse到达后立即发送给前端，
             * 不是等待完整回答后再人工切块。
             */
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

                if (!modelContentReceived) {

                    /*
                     * 模型说明直接承接确定性业务事实，
                     * 不再插入生硬的“智能说明”标题。
                     */
                    String paragraphSeparator =
                            "\n\n";

                    completeAnswer.append(
                            paragraphSeparator
                    );

                    stream.appendAnswerDelta(
                            paragraphSeparator
                    );

                    modelContentReceived = true;
                }

                modelAnswer.append(delta);
                completeAnswer.append(delta);
                stream.appendAnswerDelta(delta);
            }

            /*
             * 模型正常结束但没有返回可见文本，
             * 同样视为分析不可用。
             */
            if (!modelContentReceived) {
                completeAnswer.append(
                        MODEL_UNAVAILABLE_TEXT
                );

                stream.appendAnswerDelta(
                        MODEL_UNAVAILABLE_TEXT
                );
            }

        } catch (RuntimeException exception) {

            AgentClientDisconnectedException disconnected =
                    findClientDisconnected(exception);

            if (disconnected != null) {
                throw disconnected;
            }
            if (isRunCancelled(exception)) {
                CancellationException cancelled =
                        new CancellationException(
                                "回答已由用户终止"
                        );

                cancelled.initCause(exception);
                throw cancelled;
            }
            log.warn(
                    "工作流文字分析失败，保留确定性事实，"
                            + "runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName()
            );

            completeAnswer.append(
                    MODEL_UNAVAILABLE_TEXT
            );

            stream.appendAnswerDelta(
                    MODEL_UNAVAILABLE_TEXT
            );
        }

        String finalAnswer =
                completeAnswer.toString().trim();

        /*
         * 当前流式模型调用没有自动切换备用模型，
         * 因此成功模型与请求模型保持一致。
         */
        stream.setAnswerModelResult(
                request.getModelCode(),
                request.getModelCode()
        );

        /*
         * 先保存完整消息，再发送最终完成事件。
         * 页面刷新后仍能恢复本次文字回答。
         */
        aiChatSessionService.saveAssistantMessage(
                request.getUserId(),
                request.getConversationId(),
                finalAnswer,
                runId,
                request.getModelCode(),
                "TEXT",
                null
        );
        stream.markAssistantMessagePersisted();
        stream.finishAnswer(finalAnswer);
    }

    /**
     * 构造精简模型输入。
     *
     * getEffectiveQuestion已经包含现有会话上下文补全结果，
     * 不再把完整历史消息重复发送给模型。
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
     * 兼容两类模型流式协议：
     * 1. 每次只返回当前片段；
     * 2. 每次返回截至当前的累计文本。
     */
    private String extractDelta(
            ChatResponse response,
            StringBuilder accumulatedModelAnswer) {

        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
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
     * 从Reactor异常包装中找回客户端断开标记。
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
     * 判断模型流是否因用户主动终止而结束。
     */
    private boolean isRunCancelled(Throwable throwable) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
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