package org.example.ai.agent.business.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 使用已脱敏模型事实补充业务说明。
 */
@Service
public class BusinessAnswerModelService {

    private static final int MAX_MODEL_ENVELOPE_JSON_LENGTH = 32_000;
    private static final int MAX_QUESTION_LENGTH = 2_000;
    private static final int MAX_DATASETS = 20;
    private static final int MAX_FACTS_PER_DATASET = 200;
    private static final int MAX_TOTAL_FACTS = 1_000;
    private static final int MAX_DATASET_TEXT_LENGTH = 128;

    private static final String SYSTEM_PROMPT = """
            你是企业PM项目管理系统的业务问答助手。

            只能依据用户问题和后端提供的可信模型事实回答。
            金额、数量、比例、状态和完整性已经由后端确定，禁止重新计算或修改。
            禁止编造输入中不存在的项目、事实、风险或业务结论。
            不要重复罗列页面已经展示的全部指标和表格。
            modelFactsTruncated=true只表示本段模型分析输入被裁剪，
            不表示业务查询结果或页面数据不完整，禁止据此推断全量。
            信息不足时明确说明，不得猜测。
            使用简洁中文，只输出自然语言说明。
            """;

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;

    public BusinessAnswerModelService(
            ObjectMapper objectMapper,
            TrackedChatClientService chatClientService) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.chatClientService = Objects.requireNonNull(chatClientService, "chatClientService不能为空");
    }

    /**
     * 生成非空业务说明。
     */
    public String generate(ModelRequest request) {
        Objects.requireNonNull(request, "模型回答请求不能为空");

        PromptEnvelope envelope = buildPromptEnvelope(request);
        String envelopeJson = writeJson(envelope);
        if (utf8Length(envelopeJson) > MAX_MODEL_ENVELOPE_JSON_LENGTH) {
            throw new IllegalStateException("模型安全信封超过内部字节上限");
        }

        ModelCallContext context = ModelCallContext.builder()
                .runId(request.runId())
                .conversationId(request.conversationId())
                .userId(request.userId())
                .modelCode(request.modelCode())
                .callType(ModelCallType.ANSWER)
                .build();

        ChatResponse response = chatClientService.call(
                context,
                SYSTEM_PROMPT,
                """
                        安全模型输入JSON：
                        %s

                        截断只限制本段模型分析范围，不代表业务数据不完整，也不能据此推断全量。
                        请补充简洁说明。
                        """.formatted(envelopeJson)
        );

        String text = extractText(response);
        if (text.isBlank()) {
            throw new IllegalStateException("模型返回的业务说明为空");
        }
        return text.trim();
    }

    /**
     * 按输入顺序逐字段加入安全信封，超限字段跳过但继续尝试后续字段。
     */
    private PromptEnvelope buildPromptEnvelope(ModelRequest request) {
        String question = truncate(request.question(), MAX_QUESTION_LENGTH);
        boolean questionTruncated = question.length() < request.question().length();
        boolean factsTruncated = request.datasets().size() > MAX_DATASETS;
        List<PromptDataset> includedDatasets = new ArrayList<>();
        int includedFactCount = 0;

        int datasetCount = Math.min(request.datasets().size(), MAX_DATASETS);
        for (int datasetIndex = 0; datasetIndex < datasetCount; datasetIndex++) {
            ModelDatasetInput dataset = request.datasets().get(datasetIndex);
            String datasetCode = truncate(dataset.datasetCode(), MAX_DATASET_TEXT_LENGTH);
            String label = truncate(dataset.label(), MAX_DATASET_TEXT_LENGTH);
            Map<String, Object> includedFacts = new LinkedHashMap<>();
            int visitedFacts = 0;

            List<PromptDataset> baseCandidate = new ArrayList<>(includedDatasets);
            baseCandidate.add(new PromptDataset(datasetCode, label, Map.of()));
            // false比true多一个UTF-8字节，候选统一按更长值预留最终标志空间。
            if (utf8Length(writeJson(new PromptEnvelope(
                    question,
                    baseCandidate,
                    false,
                    questionTruncated
            ))) > MAX_MODEL_ENVELOPE_JSON_LENGTH) {
                factsTruncated = true;
                break;
            }

            for (Map.Entry<String, Object> entry : dataset.modelFacts().entrySet()) {
                if (visitedFacts >= MAX_FACTS_PER_DATASET
                        || includedFactCount >= MAX_TOTAL_FACTS) {
                    factsTruncated = true;
                    break;
                }
                visitedFacts++;

                Map<String, Object> candidateFacts = new LinkedHashMap<>(includedFacts);
                candidateFacts.put(entry.getKey(), entry.getValue());
                List<PromptDataset> candidateDatasets = new ArrayList<>(includedDatasets);
                candidateDatasets.add(new PromptDataset(datasetCode, label, candidateFacts));
                PromptEnvelope candidate = new PromptEnvelope(
                        question,
                        candidateDatasets,
                        false,
                        questionTruncated
                );
                if (utf8Length(writeJson(candidate)) <= MAX_MODEL_ENVELOPE_JSON_LENGTH) {
                    includedFacts = candidateFacts;
                    includedFactCount++;
                } else {
                    factsTruncated = true;
                }
            }

            if (dataset.modelFacts().size() > visitedFacts) {
                factsTruncated = true;
            }
            includedDatasets.add(new PromptDataset(datasetCode, label, includedFacts));
        }

        return new PromptEnvelope(
                question,
                includedDatasets,
                factsTruncated,
                questionTruncated
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("模型事实序列化失败", exception);
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("模型没有返回业务说明");
        }
        return response.getResult().getOutput().getText();
    }

    /**
     * 一次模型说明请求的安全元数据。
     */
    public record ModelRequest(
            String runId,
            String conversationId,
            String userId,
            String modelCode,
            String question,
            List<ModelDatasetInput> datasets) {

        public ModelRequest {
            runId = normalize(runId);
            conversationId = normalize(conversationId);
            userId = normalize(userId);
            modelCode = normalize(modelCode);
            question = normalize(question);
            datasets = datasets == null ? List.of() : List.copyOf(datasets);
        }
    }

    /**
     * 模型只能看到独立的modelFacts通道。
     */
    public record ModelDatasetInput(
            String datasetCode,
            String label,
            Map<String, Object> modelFacts) {

        public ModelDatasetInput {
            datasetCode = normalize(datasetCode);
            label = normalize(label);
            modelFacts = immutableMap(modelFacts);
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Object frozen = ReportDatasetValidator.freezeSafeValue(
                source == null ? Map.of() : source
        );
        validateMapKeys(frozen);
        return castMap(frozen);
    }

    private static void validateMapKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                if (key.isBlank()) {
                    throw new IllegalArgumentException("安全值Map key不能为空");
                }
                validateMapKeys(entry.getValue());
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(BusinessAnswerModelService::validateMapKeys);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 只在模型调用前存在的私有安全信封。
     */
    private record PromptEnvelope(
            String question,
            List<PromptDataset> datasets,
            boolean modelFactsTruncated,
            boolean questionTruncated) {

        private PromptEnvelope {
            datasets = List.copyOf(datasets);
        }
    }

    private record PromptDataset(
            String datasetCode,
            String label,
            Map<String, Object> modelFacts) {

        private PromptDataset {
            modelFacts = Collections.unmodifiableMap(
                    new LinkedHashMap<>(modelFacts)
            );
        }
    }
}
