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
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.modelusage.model.ModelCallContext;
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
    private static final String PROVIDER_NAME = "openai-compatible";
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final ModelUsageService modelUsageService;
    private final TokenUsageExtractor tokenUsageExtractor;
    private final FactCompletenessValidator factCompletenessValidator;
    private final DeterministicMarkdownRenderer markdownRenderer;
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
        List<AnswerFact> facts =collectFacts(toolResults);
        FactValidationResult validation =factCompletenessValidator.validate(facts);
        /*
         * 模型只接收精简事实，不接收 raw、data 和字段路径。
         */
        List<AnswerModelFact> modelFacts = buildModelFacts(facts);

        String businessDataJson = toJson(modelFacts);
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
        long startTime = System.currentTimeMillis();
        /*
         * 防止已经成功记录 Token 后，因为 LENGTH 等质量问题抛出异常，
         * catch 中再次写入一条重复失败记录。
         */
        boolean usageRecorded = false;
        try {
            /*
             * 兼容尚未产生标准事实的旧工具结果。
             *
             * 业务查询正常情况下应该存在 facts；
             * 如果 facts 为空，暂时回退到第一阶段精简上下文，
             * 避免一次性改造影响现有功能。
             */
            boolean useFactMode =facts != null && !facts.isEmpty();
            /*
             * 必须获取 ChatResponse，而不是只调用 content()，
             * 因为 Token Usage 位于响应 metadata 中。
             */
            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            long durationMs = System.currentTimeMillis() - startTime;
            if (response == null || response.getResult() == null) {
                throw new IllegalStateException("模型没有返回有效响应");
            }
            String content = response.getResult().getOutput().getText();
            /*
             * 空文本也不能当作一次成功回答。
             */
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("模型返回内容为空");
            }
            TokenUsageData usage = tokenUsageExtractor.extract(response);

            String modelName = response.getMetadata() == null ? null: response.getMetadata().getModel();

            String requestId = response.getMetadata() == null ? null : response.getMetadata().getId();

            String finishReason = extractFinishReason(response);

            recordSuccessSafely(callContext, modelName,requestId, usage,durationMs,finishReason );
            usageRecorded = true;
            /*
             * LENGTH 表示达到最大输出 Token，
             * 回答和 Markdown 很可能已经被截断，不能标记为正常完成。
             */
            if ("LENGTH".equalsIgnoreCase(finishReason)) {
                throw new IllegalStateException("模型回答达到最大输出长度，内容可能不完整");
            }
            /*
             * AI 只生成结论。
             * 关键数据、明细表格和缺失字段由 Java 确定性生成。
             */
            if (useFactMode) {
                return markdownRenderer.render(content,facts,validation );
            }
            return content;
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            /*
             * 如果模型响应尚未成功记录，保存一次失败调用。
             *
             * 如果已记录真实 Token，例如 finishReason=LENGTH，
             * 不再重复写入失败明细。
             */
            if (!usageRecorded) {
                recordFailureSafely( callContext, durationMs, e.getMessage() );
            }
            throw e;
        }
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
     * 安全保存成功调用。
     *
     * Token 统计属于辅助功能，保存失败不能影响用户正常获得回答。
     */
    private void recordSuccessSafely( ModelCallContext context,String modelName,
                                      String requestId,TokenUsageData usage,long durationMs,String finishReason ) {
        try {
            modelUsageService.recordSuccess(context, PROVIDER_NAME, modelName,
                    requestId,usage,durationMs,finishReason );
        } catch (Exception exception) {
            log.error(
                    "保存模型Token使用量失败，runId={}，错误={}",
                    context.getRunId(),
                    exception.getMessage(),
                    exception
            );
        }
    }
    /**
     * 安全保存失败调用。
     */
    private void recordFailureSafely( ModelCallContext context, long durationMs,String errorMessage) {
        try {
            modelUsageService.recordFailure( context, PROVIDER_NAME,null,durationMs,errorMessage);
        } catch (Exception exception) {
            log.error("保存模型失败调用记录异常，runId={}，错误={}", context.getRunId(), exception.getMessage(),exception );
        }
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