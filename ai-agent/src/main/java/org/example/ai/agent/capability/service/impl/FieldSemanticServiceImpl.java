package org.example.ai.agent.capability.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI字段语义生成实现。
 */
@Service
@RequiredArgsConstructor
public class FieldSemanticServiceImpl
        implements FieldSemanticService {

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
            List<FieldDictionaryVO> fieldDictionaryVOS = fields.stream()
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
            String fieldJson = objectMapper.writeValueAsString(fieldDictionaryVOS);
            String systemPrompt = """
                        你是企业PM项目管理系统字段字典助手。
                        只能根据接口名称、字段名、字段路径、类型和示例值生成候选解释。
                
                        要求：
                        1. 输出纯JSON字符串，不要输出Markdown代码块。
                        2. 不确定金额单位、状态含义时，不得猜测。
                        3. 中文名应简短，通常不超过15个汉字。
                        4. displayFormat只能是：
                           text、number、amount、percent、date、
                           status、boolean。
                        5. 必须保证字符串、对象和数组全部完整闭合。
                        6. 无法确定时，businessMeaning写“含义待确认”。
                        """;
            String userPrompt = """
                        能力名称：%s
                        能力说明：%s
                
                        字段列表：
                        %s
                
                        返回格式：
                        [
                          {
                            "fieldId": 1,
                            "fieldName": "projectCode",
                            "fieldPath": "$.data.records[].projectCode",
                            "suggestedCnName": "项目编码",
                            "suggestedMeaning": "项目唯一编号",
                            "suggestedFormat": "text",
                            "uncertain": false
                          }
                        ]
                        """.formatted(capability.getCapabilityName(),capability.getDescription(),fieldJson);

            ModelCallContext context = ModelCallContext.builder()
                    /*
                     * 字段语义生成不是一次 Agent 聊天运行，
                     * 因此没有 runId，不汇总到 ai_run_trace。
                     */
                    .callType(ModelCallType.FIELD_SEMANTIC)
                    .callSequence(1)
                    .build();
            ChatResponse response = trackedChatClientService.call(
                    context,
                    systemPrompt,
                    userPrompt
            );
            String content = response.getResult().getOutput().getText();
            return objectMapper.readValue(cleanJson(content),new TypeReference<List<FieldSemanticSuggestionVO>>() {});
        } catch (Exception e) {
            throw new BusinessException( 500,"字段语义生成失败：" + e.getMessage());
        }
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
        return content.trim()
                .replaceFirst("^```json\\s*", "")
                .replaceFirst("^```\\s*", "")
                .replaceFirst("\\s*```$", "");
    }
}