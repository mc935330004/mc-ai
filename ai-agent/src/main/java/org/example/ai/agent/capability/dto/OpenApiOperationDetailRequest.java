package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * OpenAPI 单接口详情请求。
 */
@Data
public class OpenApiOperationDetailRequest {

    @NotBlank(message = "业务系统编码不能为空")
    private String systemCode;

    @NotBlank(message = "接口地址不能为空")
    private String url;

    @NotBlank(message = "请求方法不能为空")
    private String method;
}