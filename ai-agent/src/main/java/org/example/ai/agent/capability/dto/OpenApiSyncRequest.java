package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * OpenAPI增量同步请求。
 */
@Data
public class OpenApiSyncRequest {

    @NotBlank(message = "业务系统编码不能为空")
    private String systemCode;
}