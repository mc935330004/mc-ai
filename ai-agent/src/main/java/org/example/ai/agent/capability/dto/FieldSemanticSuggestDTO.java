package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量生成字段语义建议。
 */
@Data
public class FieldSemanticSuggestDTO {

    @NotBlank(message = "能力编码不能为空")
    private String capabilityCode;

    /**
     * 为空时处理当前能力下所有未人工确认字段。
     */
    @Size(max = 50, message = "一次最多处理50个字段")
    private List<Long> fieldIds;
}