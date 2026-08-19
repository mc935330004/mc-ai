package org.example.ai.agent.workflow.answer.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.observability.AgentMetrics;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用精简可信数据执行一次报告分析。
 *
 * 模型只负责选择重点和解释事实，
 * 关键金额始终使用后端提供的真实值。
 */
@Service
@RequiredArgsConstructor
public class WorkflowReportAnalysisModelService {

    private static final int MAX_KEY_AMOUNTS = 4;
    private static final int MAX_HIGHLIGHTS = 5;
    private static final int MAX_WARNINGS = 3;
    private static final int MAX_TEXT_LENGTH = 300;

    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "[-+]?\\d[\\d,]*(?:\\.\\d+)?%?"
    );

    private static final String SYSTEM_PROMPT = """
            你是企业PM项目管理系统的报告金额分析助手。

            只能依据输入中的可信字段生成分析结果。
            后端已经完成金额、比例和数量提取，禁止重新计算。
            禁止编造项目、金额、比例、日期和状态。
            禁止输出Markdown、HTML、CSS和表格。
            禁止生成调整预算、修改合同等业务建议。
            只陈述金额、比例、数量和异常事实。

            输出要求：
            1. summary最多两句话。
            2. keyAmountKeys只能选择输入metrics中的key。
            3. highlights最多5条。
            4. warnings最多3条。
            5. 输入中不存在的数字不得出现在结果中。
            6. 只返回一个合法JSON对象，不得输出其他内容。

            JSON格式：
            {
              "summary": "简短事实摘要",
              "keyAmountKeys": ["S0:budgetTotal"],
              "highlights": ["重要事实"],
              "warnings": ["异常事实"]
            }
            """;
    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;
    private final AgentMetrics agentMetrics;
    /**
     * 执行一次报告分析。
     */
    /**
     * 执行一次报告分析，并记录模型结果是否通过本地校验。
     */
    public ReportSchemaVO.Analysis analyze(
            AgentRequest request,
            String runId,
            ReportAnalysisInput input) {

        long startedAt = System.currentTimeMillis();
        String failureReason = "INPUT_INVALID";

        try {
            validate(request, input);

            String userPrompt = buildUserPrompt(
                    request,
                    input
            );

            ModelCallContext context = ModelCallContext.builder()
                    .runId(runId)
                    .conversationId(request.getConversationId())
                    .userId(request.getUserId())
                    .callType(ModelCallType.REPORT_ANALYSIS)
                    .callSequence(1)
                    .modelCode(request.getModelCode())
                    .build();

            failureReason = "MODEL_CALL_FAILED";

            ChatResponse response = chatClientService.call(
                    context,
                    SYSTEM_PROMPT,
                    userPrompt
            );

            /*
             * 模型已经返回，后续异常归类为输出解析或可信校验失败。
             */
            failureReason = "OUTPUT_INVALID";

            JsonNode root = parseObject(
                    extractText(response)
            );

            ReportSchemaVO.Analysis analysis =
                    buildAnalysis(root, input);

            agentMetrics.recordReportAnalysisAttempt(
                    true,
                    "NONE",
                    System.currentTimeMillis() - startedAt
            );

            return analysis;
        } catch (RuntimeException exception) {
            agentMetrics.recordReportAnalysisAttempt(
                    false,
                    failureReason,
                    System.currentTimeMillis() - startedAt
            );

            throw exception;
        }
    }

    private ReportSchemaVO.Analysis buildAnalysis(
            JsonNode root,
            ReportAnalysisInput input) {

        Set<String> trustedNumbers = buildTrustedNumbers(input);

        String summary = sanitizeText(
                root.path("summary").asText(""),
                trustedNumbers
        );

        List<String> highlights = readTextList(
                root.path("highlights"),
                MAX_HIGHLIGHTS,
                trustedNumbers
        );

        List<String> warnings = readTextList(
                root.path("warnings"),
                MAX_WARNINGS,
                trustedNumbers
        );

        List<String> selectedKeys = readRawTextList(
                root.path("keyAmountKeys"),
                MAX_KEY_AMOUNTS
        );

        List<ReportSchemaVO.KeyAmount> keyAmounts = buildKeyAmounts(
                selectedKeys,
                input.metrics()
        );

        if (!StringUtils.hasText(summary)
                && highlights.isEmpty()
                && warnings.isEmpty()
                && keyAmounts.isEmpty()) {

            throw new IllegalStateException(
                    "AI分析结果没有可展示的可信内容"
            );
        }

        return new ReportSchemaVO.Analysis(
                "DONE",
                "AI",
                summary,
                keyAmounts,
                highlights,
                warnings
        );
    }

    /**
     * AI只返回金额key，实际金额继续读取后端可信指标。
     */
    private List<ReportSchemaVO.KeyAmount> buildKeyAmounts(
            List<String> selectedKeys,
            List<ReportAnalysisInput.Metric> metrics) {

        Map<String, ReportAnalysisInput.Metric> amountByKey =
                new LinkedHashMap<>();

        for (ReportAnalysisInput.Metric metric : metrics) {
            if ("AMOUNT".equals(metric.kind())) {
                amountByKey.put(metric.key(), metric);
            }
        }

        List<ReportSchemaVO.KeyAmount> result = new ArrayList<>();
        Set<String> addedKeys = new LinkedHashSet<>();

        for (String selectedKey : selectedKeys) {
            addKeyAmount(
                    amountByKey.get(selectedKey),
                    result,
                    addedKeys
            );
        }

        /*
         * 模型没有选择有效金额时，
         * 使用报告配置顺序补足关键金额。
         */
        for (ReportAnalysisInput.Metric metric : amountByKey.values()) {
            if (result.size() >= MAX_KEY_AMOUNTS) {
                break;
            }
            addKeyAmount(metric, result, addedKeys);
        }

        return List.copyOf(result);
    }

    private void addKeyAmount(
            ReportAnalysisInput.Metric metric,
            List<ReportSchemaVO.KeyAmount> target,
            Set<String> addedKeys) {

        if (metric == null
                || target.size() >= MAX_KEY_AMOUNTS
                || !addedKeys.add(metric.key())) {
            return;
        }

        target.add(new ReportSchemaVO.KeyAmount(
                metric.key(),
                metric.label(),
                metric.value(),
                metric.displayValue(),
                metric.kind(),
                metric.value().signum() < 0
                        ? "DANGER"
                        : "PRIMARY"
        ));
    }

    /**
     * 删除包含未知数字的模型文本，防止编造金额进入页面。
     */
    private String sanitizeText(
            String value,
            Set<String> trustedNumbers) {

        if (!StringUtils.hasText(value)) {
            return "";
        }

        String text = value.trim();
        if (text.length() > MAX_TEXT_LENGTH) {
            return "";
        }

        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            String normalized = normalizeNumber(matcher.group());
            if (normalized != null
                    && !trustedNumbers.contains(normalized)) {
                return "";
            }
        }

        return text;
    }

    private List<String> readTextList(
            JsonNode node,
            int limit,
            Set<String> trustedNumbers) {

        List<String> rawValues = readRawTextList(node, limit);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String rawValue : rawValues) {
            String value = sanitizeText(
                    rawValue,
                    trustedNumbers
            );

            if (StringUtils.hasText(value)) {
                result.add(value);
            }

            if (result.size() >= limit) {
                break;
            }
        }

        return List.copyOf(result);
    }

    /**
     * 同时兼容字符串和字符串数组。
     */
    private List<String> readRawTextList(
            JsonNode node,
            int limit) {

        if (node == null || node.isNull()) {
            return List.of();
        }

        if (node.isTextual()) {
            String value = node.asText("").trim();
            return StringUtils.hasText(value)
                    ? List.of(value)
                    : List.of();
        }

        if (!node.isArray()) {
            return List.of();
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (JsonNode item : node) {
            if (!item.isTextual()) {
                continue;
            }

            String value = item.asText("").trim();
            if (StringUtils.hasText(value)) {
                result.add(value);
            }

            if (result.size() >= limit) {
                break;
            }
        }

        return List.copyOf(result);
    }

    /**
     * 报告字段中的全部数字均视为可信数字。
     */
    private Set<String> buildTrustedNumbers(
            ReportAnalysisInput input) {

        Set<String> result = new LinkedHashSet<>();

        for (ReportAnalysisInput.Metric metric : input.metrics()) {
            result.add(normalizeNumber(metric.value()));
        }

        for (ReportAnalysisInput.Fact fact : input.facts()) {
            Matcher matcher = NUMBER_PATTERN.matcher(fact.value());

            while (matcher.find()) {
                String normalized = normalizeNumber(matcher.group());
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }

        result.remove(null);
        return Set.copyOf(result);
    }

    private String normalizeNumber(BigDecimal value) {
        if (value == null) {
            return null;
        }

        return value.stripTrailingZeros().toPlainString();
    }

    private String normalizeNumber(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        String normalized = value
                .replace(",", "")
                .replace("%", "")
                .replace("+", "")
                .trim();

        try {
            return new BigDecimal(normalized)
                    .stripTrailingZeros()
                    .toPlainString();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 依次尝试原始文本、代码块内容和首个JSON对象。
     */
    private JsonNode parseObject(String content) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        candidates.add(content.trim());

        String withoutFence = removeCodeFence(content);
        candidates.add(withoutFence);

        String objectText = extractObjectText(withoutFence);
        if (objectText != null) {
            candidates.add(objectText);
        }

        Exception lastException = null;

        for (String candidate : candidates) {
            try {
                JsonNode root = objectMapper.readTree(candidate);

                if (root != null && root.isObject()) {
                    return root;
                }
            } catch (Exception exception) {
                lastException = exception;
            }
        }

        throw new IllegalStateException(
                "AI分析结果无法解析为JSON对象",
                lastException
        );
    }

    private String removeCodeFence(String content) {
        String value = content.trim();

        if (!value.startsWith("```")) {
            return value;
        }

        int firstLineEnd = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");

        if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
            return value;
        }

        return value.substring(
                firstLineEnd + 1,
                lastFence
        ).trim();
    }

    private String extractObjectText(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');

        if (start < 0 || end <= start) {
            return null;
        }

        return content.substring(start, end + 1);
    }

    private String buildUserPrompt(
            AgentRequest request,
            ReportAnalysisInput input) {

        return """
                用户问题：
                %s

                后端可信报告分析数据：
                %s

                请只返回约定的JSON对象。
                """.formatted(
                safeQuestion(request),
                writeJson(input)
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "报告分析输入序列化失败",
                    exception
            );
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {

            throw new IllegalStateException(
                    "模型没有返回报告分析结果"
            );
        }

        String content = response
                .getResult()
                .getOutput()
                .getText();

        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException(
                    "模型返回的报告分析结果为空"
            );
        }

        return content.trim();
    }

    private void validate(
            AgentRequest request,
            ReportAnalysisInput input) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "用户请求不能为空"
            );
        }

        if (input == null) {
            throw new IllegalArgumentException(
                    "报告分析输入不能为空"
            );
        }
    }

    private String safeQuestion(AgentRequest request) {
        return StringUtils.hasText(
                request.getEffectiveQuestion()
        )
                ? request.getEffectiveQuestion().trim()
                : "请分析当前业务报告";
    }
}