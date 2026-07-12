package org.example.ai.agent.capability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 确认并保存AI字段语义建议。
 */
@Data
public class FieldSemanticConfirmDTO {

    @NotBlank(message = "能力编码不能为空")
    private String capabilityCode;

    @Valid
    @NotEmpty(message = "至少确认一个字段")
    private List<FieldSemanticConfirmItemDTO> fields;
}