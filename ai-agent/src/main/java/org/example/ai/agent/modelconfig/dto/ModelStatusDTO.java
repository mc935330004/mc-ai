package org.example.ai.agent.modelconfig.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 模型启停参数。
 */
@Data
public class ModelStatusDTO {

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;
}