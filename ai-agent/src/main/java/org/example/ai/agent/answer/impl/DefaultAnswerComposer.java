package org.example.ai.agent.answer.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.AnswerComposer;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        // 字段字典解释：告诉大模型业务接口返回字段分别是什么意思。
        String fieldDictionaryText = buildFieldDictionaryText(toolResults);
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
            
                    字段语义字典：
                    %s
            
                    业务工具返回数据：
                    %s
            
                    请基于以上真实业务数据生成最终回答。
            
                    要求：
                    1. 必须优先根据“字段语义字典”理解字段含义。
                    2. 如果字段字典里说明某字段是金额、比例、状态、日期，要按对应业务含义解释。
                    3. 不要把字段英文名、字段路径、capabilityCode 暴露给用户。
                    4. 不要输出原始 JSON。
                    5. 最终回答必须是 Markdown。
                    """.formatted(
                request.getUserQuestion(),
                routePlan.getGoal(),
                fieldDictionaryText,
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

    /**
     * 构建字段语义字典文本。
     * 作用：
     * 把 ToolResult 中的字段字典转成大模型容易理解的文本，
     * 避免模型只看到 contractAmount、receivedAmount 这类英文名时误解含义。
     */
    private String buildFieldDictionaryText(List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return "当前没有字段字典。";
        }
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();

        for (ToolResult result : toolResults) {
            if (result.getFields() == null || result.getFields().isEmpty()) {
                continue;
            }
            for (FieldMeta field : result.getFields()) {
                String key = field.getPath();
                if (key == null || seen.contains(key)) {
                    continue;
                }
                seen.add(key);
                builder.append("- ")
                        .append(nullToEmpty(field.getName()))
                        .append("：")
                        .append(nullToEmpty(field.getCnName()));
                if (field.getMeaning() != null && !field.getMeaning().isBlank()) {
                    builder.append("，含义：").append(field.getMeaning());
                }
                if (field.getFormat() != null && !field.getFormat().isBlank()) {
                    builder.append("，展示格式：").append(field.getFormat());
                }
                builder.append("\n");
            }
        }
        if (builder.isEmpty()) {
            return "当前没有字段字典。";
        }
        return builder.toString();
    }

    /**
     * 空字符串兜底，避免提示词里出现 null。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}