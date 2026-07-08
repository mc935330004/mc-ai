package org.example.ai.agent.capability.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 字段字典生成 DTO。
 *
 * 前端把接口返回的 JSON 原文传进来，后端自动解析字段路径。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FieldDictionaryGenerateDTO {

    /**
     * 能力编码。
     */
    private String capabilityCode;

    /**
     * 接口返回的 JSON 字符串。
     */
    private String json;
}