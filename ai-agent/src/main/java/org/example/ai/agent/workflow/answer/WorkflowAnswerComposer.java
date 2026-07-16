package org.example.ai.agent.workflow.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

/**
 * 基于工作流结构化结果生成最终回答。
 */
@Service
@RequiredArgsConstructor
public class WorkflowAnswerComposer {

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;

    public String compose(
            AgentRequest request,
            WorkflowExecutionOutcome outcome) {

        if (outcome == null) {
            return "工作流没有返回查询结果。";
        }

        if (!outcome.success()) {
            return "工作流执行失败："
                    + safeText(
                            outcome.errorMessage()
                    );
        }

        String systemPrompt = """
                你是企业PM项目管理系统的AI助手。

                系统已经执行了真实、已发布的查询工作流。
                你只负责解释结果。

                必须遵守：
                1. 只能依据提供的工作流结果回答。
                2. 不允许编造项目、金额、日期、状态。
                3. partialSuccess=true时必须明确说明部分项目失败。
                4. 必须分别说明成功数量和失败数量。
                5. 不输出workflowCode、versionId、节点ID或内部错误堆栈。
                6. 不输出原始JSON。
                7. 最多查询5个项目。
                """;

        String userPrompt = """
                用户问题：
                %s

                工作流执行结果：
                %s

                请生成清晰、简洁的中文业务回答。
                """.formatted(
                        request.getUserQuestion(),
                        writeJson(outcome)
                );

        ModelCallContext context =
                ModelCallContext.builder()
                        .runId(outcome.runId())
                        .conversationId(
                                request.getConversationId()
                        )
                        .userId(request.getUserId())
                        .callType(
                                ModelCallType.ANSWER
                        )
                        .callSequence(1)
                        .build();

        ChatResponse response =
                chatClientService.call(
                        context,
                        systemPrompt,
                        userPrompt
                );

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException(
                    "工作流回答模型没有返回内容"
            );
        }

        return response.getResult()
                .getOutput()
                .getText();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(
                    value
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "工作流回答上下文序列化失败",
                    exception
            );
        }
    }

    private String safeText(String value) {
        return value == null
                ? "未知错误"
                : value;
    }
}