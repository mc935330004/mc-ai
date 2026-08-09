package org.example.ai.agent.modelconfig.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 单个授权模型参数。
 */
@Data
public class ModelAssignmentItemDTO {

    @NotBlank(message = "模型编码不能为空")
    private String modelCode;

    @NotNull(message = "默认模型标识不能为空")
    private Boolean defaultModel = false;

    @NotNull(message = "备用优先级不能为空")
    @Min(value = 1, message = "备用优先级必须大于0")
    private Integer fallbackPriority;
}