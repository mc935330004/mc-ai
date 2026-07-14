package org.example.ai.agent.capability.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.dto.*;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.FieldSemanticService;
import org.example.ai.agent.capability.vo.FieldDictionaryVO;
import org.example.ai.agent.capability.vo.FieldSemanticSuggestionVO;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI字段语义生成实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FieldSemanticServiceImpl
        implements FieldSemanticService {

    /**
     * 单次发送给模型的最大字段数。
     *
     * 字段语义返回内容明显大于普通分类结果，
     * 使用小批次可以避免触发模型最大输出 Token 限制。
     */
    private static final int FIELD_BATCH_SIZE = 8;

    private final ObjectMapper objectMapper;
    private final FieldDictionaryMapper fieldDictionaryMapper;
    private final CapabilityDefinitionMapper capabilityDefinitionMapper;
    private final TrackedChatClientService trackedChatClientService;

    @Override
    public List<FieldSemanticSuggestionVO> suggest(FieldSemanticSuggestDTO dto) {
        CapabilityDefinition capability =findCapability(dto.getCapabilityCode());
        List<FieldDictionary> fields = selectPendingFields(dto);
        if (fields.isEmpty()) {
            return List.of();
        }
        try {
            AtomicInteger callSequence = new AtomicInteger(1);
            List<FieldSemanticSuggestionVO> result = new ArrayList<>();
            /*
             * 先按固定大小分批，避免一次请求生成过多 JSON。
             * 某一批仍被截断时，generateWithAdaptiveSplit 会继续二分。
             */
            for (int start = 0; start < fields.size(); start += FIELD_BATCH_SIZE) {
                int end = Math.min(start + FIELD_BATCH_SIZE, fields.size());
                result.addAll(generateWithAdaptiveSplit(
                        capability,
                        fields.subList(start, end),
                        callSequence));
            }
            return result;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception e) {
            throw new BusinessException( 500,"字段语义生成失败：" + e.getMessage());
        }
    }

    /**
     * 生成一批字段语义。
     *
     * 如果模型输出被截断、JSON 不完整或缺少字段，
     * 将当前批次二分后重试，避免对不完整 JSON 进行补括号修复。
     */
    private List<FieldSemanticSuggestionVO> generateWithAdaptiveSplit(
            CapabilityDefinition capability,
            List<FieldDictionary> fields,
            AtomicInteger callSequence) {
        try {
            return generateSingleBatch(capability, fields, callSequence);
        } catch (RetryableSemanticResponseException exception) {
            log.warn(
                    "字段语义模型响应不完整，准备拆分批次，capabilityCode={}，fieldCount={}，reason={}",
                    capability.getCapabilityCode(),
                    fields.size(),
                    exception.getMessage()
            );

            if (fields.size() <= 1) {
                String fieldName = fields.isEmpty() ? "UNKNOWN"
                        : fields.get(0).getFieldName();
                throw new BusinessException( 502, "字段语义生成失败：模型对字段“"
                                + fieldName
                                + "”仍返回不完整结果，请稍后重试" );
            }

            int middle = fields.size() / 2;
            List<FieldSemanticSuggestionVO> result = new ArrayList<>();
            result.addAll(generateWithAdaptiveSplit(
                    capability,
                    fields.subList(0, middle),
                    callSequence
            ));
            result.addAll(generateWithAdaptiveSplit(
                    capability,
                    fields.subList(middle, fields.size()),
                    callSequence
            ));
            return result;
        }
    }

    /**
     * 调用模型生成单个批次。
     */
    private List<FieldSemanticSuggestionVO> generateSingleBatch( CapabilityDefinition capability,
            List<FieldDictionary> fields,AtomicInteger callSequence) {
        List<FieldDictionaryVO> fieldContexts = fields.stream()
                .map(field -> FieldDictionaryVO.builder()
                        .fieldId(field.getId())
                        .fieldName(field.getFieldName())
                        .fieldCnName(field.getFieldCnName())
                        .fieldPath(field.getFieldPath())
                        .fieldType(field.getDisplayFormat())
                        .businessMeaning(field.getBusinessMeaning())
                        .exampleValue(field.getExampleValue())
                        .requiredOutput(field.getRequiredOutput())
                        .visible(field.getVisible())
                        .displayOrder(field.getDisplayOrder())
                        .displayGroup(field.getDisplayGroup())
                        .nullDisplayText(field.getNullDisplayText())
                        .build())
                .toList();

        String fieldJson;
        try {
            fieldJson = objectMapper.writeValueAsString(fieldContexts);
        } catch (JsonProcessingException exception) {
            throw new BusinessException( 500,"字段语义输入序列化失败：" + exception.getOriginalMessage());
        }

        String systemPrompt = """
                你是企业 PM 项目管理系统字段字典助手。
                只能根据接口名称、字段名、字段路径、类型和示例值生成候选解释。

                严格要求：
                1. 只输出一个完整 JSON 数组，不要输出 Markdown 和解释文字。
                2. 每个输入字段必须且只能返回一条结果，fieldId 必须原样复制。
                3. 不要返回 fieldName 和 fieldPath，这两个字段由系统确定性回填。
                4. 不确定金额单位、状态含义时不得猜测。
                5. suggestedCnName 应简短，通常不超过 15 个汉字。
                6. suggestedFormat 只能是：text、number、amount、percent、date、status、boolean。
                7. 无法确定时，suggestedMeaning 写“含义待确认”，uncertain 写 true。
                8. 必须保证字符串、对象和数组完整闭合。
                """;

        String userPrompt = """
                能力名称：%s
                能力说明：%s

                本批字段列表：
                %s

                返回格式：
                [
                  {
                    "fieldId": 1,
                    "suggestedCnName": "项目编码",
                    "suggestedMeaning": "项目唯一编号",
                    "suggestedFormat": "text",
                    "uncertain": false
                  }
                ]
                """.formatted(
                capability.getCapabilityName(),
                capability.getDescription(),
                fieldJson
        );

        ModelCallContext context = ModelCallContext.builder()
                /*
                 * 分批和截断重试都会产生真实模型调用，
                 * callSequence 必须逐次递增，保证 Token 明细可复盘。
                 */
                .callType(ModelCallType.FIELD_SEMANTIC)
                .callSequence(callSequence.getAndIncrement())
                .build();

        ChatResponse response = trackedChatClientService.call(
                context,
                systemPrompt,
                userPrompt
        );

        String finishReason = extractFinishReason(response);
        if ("LENGTH".equalsIgnoreCase(finishReason)) {
            throw new RetryableSemanticResponseException(
                    "finishReason=LENGTH"
            );
        }

        String content = response.getResult().getOutput().getText();
        final List<FieldSemanticSuggestionVO> suggestions;
        try {
            suggestions = objectMapper.readValue(
                    cleanJson(content),
                    new TypeReference<List<FieldSemanticSuggestionVO>>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            throw new RetryableSemanticResponseException(
                    "JSON 解析失败：" + exception.getOriginalMessage(),
                    exception
            );
        }

        return validateAndNormalizeSuggestions(fields, suggestions);
    }

    /**
     * 校验模型是否完整返回本批字段，并回填不可由模型修改的字段信息。
     */
    private List<FieldSemanticSuggestionVO> validateAndNormalizeSuggestions(
            List<FieldDictionary> fields,
            List<FieldSemanticSuggestionVO> suggestions
    ) {
        Map<Long, FieldDictionary> expectedFields = new LinkedHashMap<>();
        for (FieldDictionary field : fields) {
            expectedFields.put(field.getId(), field);
        }

        Map<Long, FieldSemanticSuggestionVO> suggestionById = new LinkedHashMap<>();
        if (suggestions != null) {
            for (FieldSemanticSuggestionVO suggestion : suggestions) {
                if (suggestion == null
                        || suggestion.getFieldId() == null
                        || !expectedFields.containsKey(suggestion.getFieldId())
                        || suggestionById.putIfAbsent(
                                suggestion.getFieldId(),
                                suggestion
                        ) != null) {
                    throw new RetryableSemanticResponseException(
                            "模型返回了未知、空值或重复的 fieldId"
                    );
                }
            }
        }

        if (suggestionById.size() != expectedFields.size()) {
            throw new RetryableSemanticResponseException(
                    "模型未完整返回本批全部字段"
            );
        }

        List<FieldSemanticSuggestionVO> result = new ArrayList<>();
        for (FieldDictionary field : fields) {
            FieldSemanticSuggestionVO suggestion = suggestionById.get(field.getId());

            /*
             * 英文字段名和 JSON 路径来自数据库，
             * 禁止使用模型生成值，避免字段映射被模型改写。
             */
            suggestion.setFieldName(field.getFieldName());
            suggestion.setFieldPath(field.getFieldPath());
            suggestion.setSuggestedFormat(
                    normalizeFormat(suggestion.getSuggestedFormat())
            );

            if (!StringUtils.hasText(suggestion.getSuggestedCnName())) {
                suggestion.setSuggestedCnName(
                        StringUtils.hasText(field.getFieldCnName())
                                ? field.getFieldCnName()
                                : field.getFieldName()
                );
                suggestion.setUncertain(true);
            }

            if (!StringUtils.hasText(suggestion.getSuggestedMeaning())) {
                suggestion.setSuggestedMeaning("含义待确认");
                suggestion.setUncertain(true);
            }

            if (suggestion.getUncertain() == null) {
                suggestion.setUncertain(true);
            }
            result.add(suggestion);
        }
        return result;
    }

    /**
     * 读取模型结束原因。
     */
    private String extractFinishReason(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getMetadata() == null
                || response.getResult().getMetadata().getFinishReason() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer confirm(FieldSemanticConfirmDTO dto) {
        findCapability(dto.getCapabilityCode());
        int updated = 0;

        for (FieldSemanticConfirmItemDTO item : dto.getFields()) {
            FieldDictionary field =fieldDictionaryMapper.selectById(item.getFieldId());

            if (field == null || !dto.getCapabilityCode().equals(
                            field.getCapabilityCode())) {
                throw new BusinessException( 400,"字段不属于当前能力：" + item.getFieldId());
            }

            field.setFieldCnName( item.getFieldCnName().trim());
            field.setBusinessMeaning(item.getBusinessMeaning().trim());
            field.setDisplayFormat(normalizeFormat(item.getDisplayFormat()) );

            // AI生成但经过人工确认，后续同步不能覆盖。
            field.setSourceType("AI");
            field.setManualOverride(1);
            field.setPublishStatus("PUBLISHED");
            field.setUpdatedAt(LocalDateTime.now());
            updated += fieldDictionaryMapper.updateById(field);
        }
        return updated;
    }

    private List<FieldDictionary> selectPendingFields(FieldSemanticSuggestDTO dto) {
        return fieldDictionaryMapper.selectList(new com.baomidou.mybatisplus.core.conditions
                .query.LambdaQueryWrapper<FieldDictionary>()
                .eq(FieldDictionary::getCapabilityCode, dto.getCapabilityCode())
                .eq(FieldDictionary::getManualOverride,0 )
                .in(dto.getFieldIds() != null && !dto.getFieldIds().isEmpty(),
                        FieldDictionary::getId,dto.getFieldIds())
                .last("LIMIT 30"));
    }

    private CapabilityDefinition findCapability(String capabilityCode) {
        CapabilityDefinition capability = capabilityDefinitionMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CapabilityDefinition>()
                        .eq(CapabilityDefinition::getCapabilityCode, capabilityCode));
        if (capability == null) {
            throw new BusinessException( 404,"能力不存在：" + capabilityCode);
        }
        return capability;
    }

    private String normalizeFormat(String format) {
        if (!StringUtils.hasText(format)) {
            return "text";
        }
        String value = format.trim().toLowerCase();
        List<String> allowed = List.of(
                "text",
                "number",
                "amount",
                "percent",
                "date",
                "status",
                "boolean"
        );
        return allowed.contains(value) ? value : "text";
    }

    /**
     * 清理模型可能返回的Markdown代码块。
     */
    private String cleanJson(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(500,"大模型没有返回字段语义");
        }
        String normalized = content.trim()
                .replaceFirst("^```json\\s*", "")
                .replaceFirst("^```\\s*", "")
                .replaceFirst("\\s*```$", "");

        /*
         * 允许模型在完整数组前后附带少量解释文本，
         * 但绝不为截断 JSON 自动补括号。
         */
        int arrayStart = normalized.indexOf('[');
        int arrayEnd = normalized.lastIndexOf(']');
        if (arrayStart < 0 || arrayEnd < arrayStart) {
            throw new RetryableSemanticResponseException(
                    "模型没有返回完整 JSON 数组"
            );
        }
        return normalized.substring(arrayStart, arrayEnd + 1);
    }

    /**
     * 表示当前模型响应可以通过缩小批次重新生成。
     */
    private static class RetryableSemanticResponseException
            extends RuntimeException {

        private RetryableSemanticResponseException(String message) {
            super(message);
        }

        private RetryableSemanticResponseException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }
}
