package org.example.ai.agent.answer.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.answer.AnswerComposer;
import org.example.ai.agent.answer.model.*;
import org.example.ai.agent.answer.render.DeterministicMarkdownRenderer;
import org.example.ai.agent.answer.validator.FactCompletenessValidator;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.config.AgentAnswerProperties;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.modelusage.model.TokenUsageData;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.modelusage.support.TokenUsageExtractor;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认答案组装器。
 *
 * 第一版只做一件事：
 * 基于真实业务工具结果生成回答，不编造数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultAnswerComposer implements AnswerComposer {

    private final ObjectMapper objectMapper;
    private final FactCompletenessValidator factCompletenessValidator;
    private final DeterministicMarkdownRenderer markdownRenderer;
    private final AgentAnswerProperties answerProperties;

    private final TrackedChatClientService trackedChatClientService;
    @Override
    public String compose(AgentRequest request, RoutePlan routePlan, List<ToolResult> toolResults) {
        // 如果工具结果为空，直接返回兜底回答，避免让模型凭空发挥。
        if (toolResults == null || toolResults.isEmpty()) {
            return "没有查询到可用于回答的业务数据。";
        }
        // 如果存在失败步骤，直接返回失败信息，不让模型包装成成功结果。
        ToolResult failedResult = findFirstFailedResult(toolResults);
        if (failedResult != null) {
            return "业务数据查询失败：" + failedResult.getErrorMessage() + "。能力编码："
                    + failedResult.getCapabilityCode();
        }
        //只构建模型真正需要的精简上下文。
//        List<AnswerToolContext> answerContexts =buildAnswerContexts(toolResults);
        /*
         * 收集标准事实并执行完整性检查。
         */
//        List<AnswerFact> facts =collectFacts(toolResults);
//        FactValidationResult validation =factCompletenessValidator.validate(facts);
//        /*
//         * 模型只接收精简事实，不接收 raw、data 和字段路径。
//         */
//        List<AnswerModelFact> modelFacts = buildModelFacts(facts);
//
//        String businessDataJson = toJson(modelFacts);
        List<AnswerFact> facts = collectFacts(toolResults);

        FactValidationResult validation = factCompletenessValidator.validate(facts);
        /*
         * structuredEnabled=false 时回退到历史精简上下文，
         * 但仍然禁止发送 raw 和完整 ToolResult。
         */
        boolean useFactMode =answerProperties.isStructuredEnabled()
                        && facts != null
                        && !facts.isEmpty();

        Object modelContext = useFactMode
                ? buildModelFacts(facts)
                : buildAnswerContexts(toolResults);
        String businessDataJson = toJson(modelContext);
//        // 字段字典解释：告诉大模型业务接口返回字段分别是什么意思。
//        String fieldDictionaryText = buildFieldDictionaryText(toolResults);
        String systemPrompt = """
                 你是企业PM项目管理系统的AI分析助手。
                
                 系统已经完成真实业务数据查询和字段格式化。
                 你只负责生成简洁的业务结论，不负责生成数据表格。
        
                 回答规则：
                 1. 只能使用系统提供的事实，不得编造。
                 2. 不得修改金额、比例、日期和状态。
                 3. 结论控制在1到3段。
                 4. 如果存在明显风险，可以补充最多3条风险说明。
                 5. 不要重复输出完整明细。
                 6. 不要生成Markdown表格。
                 7. 不输出能力编码、JSON路径和内部信息。
                 8. 不要直接输出原始JSON。
                """;

        String userPrompt = """
                用户问题：
                    %s
            
                    任务目标：
                    %s
            
                    本次真实业务事实：
                    %s

                    请生成简洁、准确的业务结论。
                    明细数据将由系统自动展示，不需要你重复生成表格。
                    """.formatted(
                            safeText(request.getUserQuestion()),
                            routePlan == null ? "": safeText(routePlan.getGoal()),
                            businessDataJson
                     );
        ModelCallContext callContext = ModelCallContext.builder()
                .runId(routePlan == null ? null : routePlan.getRunId())
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                .callType(ModelCallType.ANSWER)
                .callSequence(1)
                .build();
        /*
         * 模型调用、Token、耗时、异常记录统一由
         * TrackedChatClientService 完成。
         */
        ChatResponse response = trackedChatClientService.call(
                callContext,
                systemPrompt,
                userPrompt);
        String content = response.getResult().getOutput().getText();
        String finishReason = extractFinishReason(response);
        /*
         * LENGTH 表示模型输出可能被截断。
         *
         * Token 已经由统一调用服务记录，
         * 此处只负责回答质量判断，不重复写入失败记录。
         */
        if ("LENGTH".equalsIgnoreCase(finishReason)) {
            throw new IllegalStateException( "模型回答达到最大输出长度，内容可能不完整" );
        }
        if (useFactMode && answerProperties.isDeterministicMarkdownEnabled()) {
            return markdownRenderer.render(content, facts, validation);
        }
        return content;
    }

    /**
     * 构建发送给模型的精简上下文。
     */
    private List<AnswerToolContext> buildAnswerContexts( List<ToolResult> toolResults) {
        List<AnswerToolContext> contexts = new ArrayList<>();

        for (ToolResult result : toolResults) {
            if (result == null || !result.isSuccess()) {
                continue;
            }
            contexts.add(AnswerToolContext.builder()
                    .capabilityCode(result.getCapabilityCode())
                    .summary(result.getSummary())
                    .data(result.getData())
                    .fields(buildFieldContexts(result))
                    .build());
        }
        return contexts;
    }
    /**
     * 构建精简字段字典。
     *
     * 去重键必须包含 capabilityCode，
     * 避免多个能力具有相同字段路径时被错误去重。
     */
    private List<AnswerFieldContext> buildFieldContexts(ToolResult result) {
        if (result.getFields() == null || result.getFields().isEmpty()) {
            return List.of();
        }

        List<AnswerFieldContext> fields = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (FieldMeta field : result.getFields()) {
            if (field == null) {
                continue;
            }
            String uniqueKey = safeText(result.getCapabilityCode())+ ":" + safeText(field.getPath());
            if (!seen.add(uniqueKey)) {
                continue;
            }
            String label = StringUtils.hasText(field.getCnName()) ? field.getCnName() : field.getName();

            //没有展示名称、业务含义和格式的空字典不发送给模型，减少无意义的输入 Token。
            if (!StringUtils.hasText(label) && !StringUtils.hasText(field.getMeaning()) && !StringUtils.hasText(field.getFormat())) {
                continue;
            }
            fields.add(AnswerFieldContext.builder()
                    .label(label)
                    .meaning(field.getMeaning())
                    .format(field.getFormat())
                    .build());
        }

        return fields;
    }

    /**
     * 从模型结果中读取结束原因。
     *
     * 不同模型供应商的 metadata 实现可能不同，
     * 因此这里使用 String.valueOf 做兼容。
     */
    private String extractFinishReason(ChatResponse response) {
        if (response == null  || response.getResult() == null  || response.getResult().getMetadata() == null
                || response.getResult().getMetadata().getFinishReason() == null) {
            return null;
        }
        return String.valueOf(response.getResult().getMetadata().getFinishReason());
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
        } catch (JsonProcessingException exception) {
            log.error("回答上下文序列化失败", exception);
            return "[]";
        }
    }

    /**
     * 将空字符串转换为空文本，防止提示词中出现 null。
     */
    private String safeText(String value) {
        return value == null ? "" : value;
    }
    /**
     * 收集全部成功工具结果中的标准事实。
     */
    private List<AnswerFact> collectFacts(List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return List.of();
        }
        return toolResults.stream().filter(result ->
                        result != null&& result.isSuccess() && result.getFacts() != null)
                .flatMap(result ->result.getFacts().stream() ).toList();
    }

    /**
     * 构建发送给模型的精简事实。
     *
     * 必答字段优先，最多发送80个事实，
     * 防止大列表导致输入Token失控。
     */
    private List<AnswerModelFact> buildModelFacts(List<AnswerFact> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        return facts.stream()
                .filter(fact -> !fact.isMissing())
                .sorted((left, right) -> {
                    /*
                     * 必答字段优先发送给模型。
                     */
                    int requiredCompare =Boolean.compare(right.isRequired(),left.isRequired());
                    if (requiredCompare != 0) {
                        return requiredCompare;
                    }
                    int leftOrder =left.getDisplayOrder() == null
                                    ? 0
                                    : left.getDisplayOrder();

                    int rightOrder =right.getDisplayOrder() == null
                                    ? 0
                                    : right.getDisplayOrder();

                    return Integer.compare(leftOrder,rightOrder);
                }) .limit(80) .map(fact -> AnswerModelFact.builder()
                        .label(fact.getLabel())
                        .value(fact.getDisplayValue())
                        .meaning(fact.getMeaning())
                        .required(fact.isRequired())
                        .group(fact.getDisplayGroup())
                        .build())
                .toList();
    }
}