package org.example.ai.agent.workflow.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 根据工作流结构化结果生成最终中文回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAnswerComposer {

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;
    private final WorkflowAnswerFieldContextResolver  fieldContextResolver;
    private final WorkflowAnswerPayloadFactory answerPayloadFactory;

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

        WorkflowAnswerFieldPolicy fieldPolicy;

        try {
            fieldPolicy = fieldContextResolver.resolvePolicy(outcome);
        } catch (Exception exception) {

            /*
             * 无法确认字段可见性时必须失败关闭，
             * 不能把完整workflowData发送给模型。
             */
            log.error("工作流字段展示策略加载失败，已阻止业务数据发送给模型，runId={}，原因={}",
                    outcome.runId(),
                    safeText(exception.getMessage()));

            return "查询已经完成，但字段展示策略加载失败。"
                    + "为保护业务数据，本次未生成详细回答，"
                    + "请管理员检查字段字典发布状态。";
        }

        WorkflowAnswerModelPayload modelPayload =
                answerPayloadFactory.create(
                        outcome,
                        fieldPolicy.hiddenFieldNames()
                );

        String systemPrompt = """
                你是企业PM项目管理系统的AI助手。

                系统已经执行了真实、已发布的工作流。
                你只负责解释工作流返回的结构化结果。

                必须遵守：
                1. 只能依据提供的工作流结果回答。
                2. 不允许编造项目、金额、日期、状态或业务记录。
                3. partialSuccess=true时，必须明确说明存在部分成功。
                4. batches表示用户输入项目的执行结果。
                5. batches.descendants表示项目下的业务明细统计。
                6. PARTIAL_SUCCESS表示项目返回了可用数据，但部分明细失败或跳过。
                7. SKIPPED_NO_ID表示列表记录没有id，因此未调用详情接口，不能描述为接口调用失败。
                8. 必须分别说明成功、部分成功、失败和跳过数量。
                9. 不输出workflowCode、versionId、节点ID、能力编码或内部异常堆栈。
                10. 不向用户输出原始JSON。
                11. 用户最多输入5个项目，但业务系统返回的明细数量不受此限制。
                12. 字段语义用于解释英文机器字段，回答时优先使用中文label。
                13. 不直接向用户输出fieldName、capabilityCode或字段路径。
                14. 字段format只表示展示类型，不能自行改变数值单位或进行未经定义的换算。
                15. 字段meaning存在时，应按照meaning解释业务含义。
                """;

        String userPrompt = """
                    用户问题：
                    %s
            
                    字段中文语义：
                    %s
            
                    工作流安全结果：
                    %s
            
                    请生成清晰、准确、简洁的中文业务回答。
                    """.formatted(request.getUserQuestion(),
                            writeJson(fieldPolicy.visibleFields()),
                            writeJson(modelPayload));

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

        if (response == null || response.getResult() == null || response.getResult() .getOutput() == null) {
            throw new IllegalStateException("工作流回答模型没有返回内容");
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