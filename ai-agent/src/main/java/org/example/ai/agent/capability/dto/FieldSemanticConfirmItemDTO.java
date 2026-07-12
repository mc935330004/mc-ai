package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单个字段语义确认结果。
 */
@Data
public class FieldSemanticConfirmItemDTO {

    @NotNull(message = "字段ID不能为空")
    private Long fieldId;

    @NotBlank(message = "字段中文名不能为空")
    private String fieldCnName;

    @NotBlank(message = "业务含义不能为空")
    private String businessMeaning;

    private String displayFormat;
}