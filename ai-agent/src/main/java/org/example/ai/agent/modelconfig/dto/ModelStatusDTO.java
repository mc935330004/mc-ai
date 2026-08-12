package org.example.ai.agent.modelconfig.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 模型启停参数。
 */
@Data
public class ModelStatusDTO {

    /**
     * 目标启用状态。
     */
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    /**
     * 当前模型配置版本。
     *
     * 防止管理员使用旧页面覆盖最新配置。
     */
    @NotNull(message = "配置版本不能为空")
    private Integer version;
}