package org.example.ai.agent.answer.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.AnswerComposer;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 默认答案组装器。
 *
 * 第一版只做一件事：
 * 基于真实业务工具结果生成回答，不编造数据。
 */
@Service
@RequiredArgsConstructor
public class DefaultAnswerComposer implements AnswerComposer {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    @Override
    public String compose(AgentRequest request, RoutePlan routePlan, List<ToolResult> toolResults) {
        // 如果工具结果为空，直接返回兜底回答，避免让模型凭空发挥。
        if (toolResults == null || toolResults.isEmpty()) {
            return "没有查询到可用于回答的业务数据。";
        }

        // 如果存在失败步骤，直接返回失败信息，不让模型包装成成功结果。
        ToolResult failedResult = findFirstFailedResult(toolResults);
        if (failedResult != null) {
            return "业务数据查询失败："
                    + failedResult.getErrorMessage()
                    + "。能力编码："
                    + failedResult.getCapabilityCode();
        }

        String businessDataJson = toJson(toolResults);

        String systemPrompt = """
                 你是企业 PM 项目管理系统的 AI 助手。
                        你只能基于系统提供的业务数据回答用户问题，不能编造任何不存在的数据。
                
                        输出格式要求：
                        1. 必须使用 Markdown 格式输出。
                        2. 第一段用二级标题：## 结论。
                        3. 如果有明细数据，使用 Markdown 表格展示。
                        4. 如果有风险、异常、缺失数据，使用列表说明。
                        5. 金额、产值、比例等数字必须来自业务数据。
                        6. 如果业务数据中没有某个字段，就明确写“当前数据中未提供”。
                        7. 不要暴露 capabilityCode、JSON 字段路径、系统内部执行步骤。
                        8. 不要把原始 JSON 直接输出给用户。
                        9. 回答要简洁、实用，适合企业项目管理人员阅读。
                
                        推荐输出结构：
                
                        ## 结论
                
                        用 1-3 句话总结本次查询结果。
                
                        ## 关键数据
                
                        | 指标 | 数值 | 说明 |
                        |---|---:|---|
                        | 产值 | xxx | xxx |
                
                        ## 明细说明
                
                        - xxx
                        - xxx
                
                        ## 注意事项
                
                        - 如果没有异常，写“暂未发现明显异常。”
                """;

        String userPrompt = """
                用户问题：
                %s
        
                本次计划目标：
                %s
        
                业务工具返回数据：
                %s
        
                请基于以上真实业务数据生成最终回答。
                注意：最终回答必须是 Markdown，不要输出 JSON。
                """.formatted(
                request.getUserQuestion(),
                routePlan.getGoal(),
                businessDataJson
        );

        // 调用 Spring AI ChatClient 生成最终回答。
        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    /**
     * 查找第一个失败的工具结果。
     */
    private ToolResult findFirstFailedResult(List<ToolResult> toolResults) {
        return toolResults.stream()
                .filter(result -> !result.isSuccess())
                .findFirst()
                .orElse(null);
    }

    /**
     * 对象转 JSON。
     *
     * 如果序列化失败，返回空数组，避免主流程异常。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}