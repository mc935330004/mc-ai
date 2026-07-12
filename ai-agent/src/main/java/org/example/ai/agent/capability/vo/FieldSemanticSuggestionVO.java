package org.example.ai.agent.capability.vo;

import lombok.Data;

/**
 * AI 生成的字段语义建议。
 */
@Data
public class FieldSemanticSuggestionVO {

    private Long fieldId;

    private String fieldName;

    private String fieldPath;

    /**
     * 建议中文名称。
     */
    private String suggestedCnName;

    /**
     * 建议业务含义。
     */
    private String suggestedMeaning;

    /**
     * 建议展示格式。
     */
    private String suggestedFormat;

    /**
     * 无法确定业务含义时为true。
     */
    private Boolean uncertain;
}