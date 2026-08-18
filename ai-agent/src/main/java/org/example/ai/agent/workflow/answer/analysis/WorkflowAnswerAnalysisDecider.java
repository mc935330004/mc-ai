package org.example.ai.agent.workflow.answer.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPreparation;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunk;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 报告分析判定组件。
 *
 * 由模型判断本次业务查询结果是否值得生成 AI 分析。
 * 不写死任何敏感词，判定依据：
 * 1. 用户问题是否明确要求分析；
 * 2. 字段中文语义；
 * 3. 数据规模；
 * 4. 数据抽样内容。
 *
 * 判定失败、超时或配置关闭时默认不分析，保证性能优先、行为可预期。
 */
@Slf4j
@Service
public class WorkflowAnswerAnalysisDecider {

    private static final String SYSTEM_PROMPT = """
            你是企业PM项目管理系统的报告分析决策助手。

            你的任务：判断本次业务查询结果是否值得生成 AI 分析。
            只有数据中包含值得解读的重要事实、异常、风险或趋势时才需要分析；
            普通的数据罗列、简单查询结果不需要分析。

            判定依据：
            1. 用户问题是否明确要求分析、解读、评估、对比、发现异常或趋势。
            2. 字段语义：金额、成本、进度、状态等关键业务字段组合出现时更值得分析。
            3. 数据规模：数据量过小（1~2条记录）通常不需要分析。
            4. 数据内容：是否存在明显异常、大额差异、状态异常或风险信号。

            必须只返回一个合法JSON对象，禁止输出任何其他内容：
            {"analysisRequired": true或false, "reason": "一句话说明判断原因"}

            保守原则：不确定时返回false，避免浪费资源和用户等待时间。
            """;

    private final TrackedChatClientService chatClientService;

    private final ObjectMapper objectMapper;

    private final WorkflowAnswerAnalysisProperties properties;

    public WorkflowAnswerAnalysisDecider(
            TrackedChatClientService chatClientService,
            ObjectMapper objectMapper,
            WorkflowAnswerAnalysisProperties properties) {
        this.chatClientService = chatClientService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 判断本次报告是否需要 AI 分析。
     *
     * @return true 需要分析；false 不需要（含判定失败、配置关闭）。
     */
    public boolean decide(
            AgentRequest request,
            String runId,
            WorkflowAnswerPreparation preparation) {

        if (!properties.isDecisionEnabled()) {
            return false;
        }

        long startedAt = System.currentTimeMillis();
        try {
            WorkflowAnswerChunkPlan plan = preparation.chunkPlan();
            String userPrompt = buildUserPrompt(request, preparation, plan);

            ModelCallContext context = ModelCallContext.builder()
                    .runId(runId)
                    .conversationId(request.getConversationId())
                    .userId(request.getUserId())
                    .callType(ModelCallType.ANSWER)
                    .callSequence(plan.totalChunks() + 1)
                    .modelCode(request.getModelCode())
                    .build();

            ChatResponse response = chatClientService.call(
                    context,
                    SYSTEM_PROMPT,
                    userPrompt
            );

            JsonNode root = objectMapper.readTree(extractText(response));
            boolean required = root.path("analysisRequired").asBoolean(false);

            log.info(
                    "AI分析判定完成，runId={}，analysisRequired={}，耗时={}ms，reason={}",
                    runId,
                    required,
                    System.currentTimeMillis() - startedAt,
                    root.path("reason").asText("")
            );
            return required;
        } catch (Exception exception) {
            /*
             * 判定失败默认不分析：
             * 用户仍然能看到完整的业务报告，只是没有 AI 解读。
             */
            log.warn(
                    "AI分析判定失败，默认不分析，runId={}，errorType={}",
                    runId,
                    exception.getClass().getSimpleName()
            );
            return false;
        }
    }

    /**
     * 构造判定提示词。
     *
     * 只读取安全分块中的抽样内容，不读取原始业务数据。
     */
    private String buildUserPrompt(
            AgentRequest request,
            WorkflowAnswerPreparation preparation,
            WorkflowAnswerChunkPlan plan) {

        String sample = buildDataSample(plan);

        return """
                用户问题：
                %s

                字段中文语义：
                %s

                数据规模：
                - 分块总数：%d
                - 原始字符数量：%d
                - 分块字符总量：%d

                数据抽样（只用于判断，不是完整数据）：
                %s

                请判断本次查询结果是否需要 AI 分析。
                """.formatted(
                safeText(
                        request.getEffectiveQuestion(),
                        "用户未提供具体问题"
                ),
                safeText(
                        preparation.fieldSemanticsJson(),
                        "[]"
                ),
                plan.totalChunks(),
                plan.sourceCharCount(),
                plan.chunkCharCount(),
                safeText(sample, "（无数据可抽样）")
        );
    }

    /**
     * 从第一个安全分块中截取抽样内容。
     *
     * 受 sampleMaxChars 限制，保证判定调用快速完成。
     */
    private String buildDataSample(WorkflowAnswerChunkPlan plan) {
        if (plan.chunks().isEmpty()) {
            return "";
        }
        WorkflowAnswerChunk first = plan.chunks().get(0);
        String payload = first.payloadJson();
        if (payload == null || payload.isBlank()) {
            return "";
        }
        int maxChars = properties.getSampleMaxChars();
        return payload.length() <= maxChars
                ? payload
                : payload.substring(0, maxChars);
    }

    /**
     * 解析模型返回的文本内容。
     */
    private String extractText(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException("模型没有返回分析判定结果");
        }
        String content = response.getResult().getOutput().getText();
        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException("模型返回的分析判定结果为空");
        }
        return content.trim();
    }

    private String safeText(String value, String defaultValue) {
        return StringUtils.hasText(value)
                ? value.trim()
                : defaultValue;
    }
}
