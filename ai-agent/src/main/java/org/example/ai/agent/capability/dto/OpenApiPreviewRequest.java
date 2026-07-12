package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * OpenAPI 扫描预览请求。
 */
@Data
public class OpenApiPreviewRequest {

    /**
     * 业务系统编码。
     */
    @NotBlank(message = "业务系统编码不能为空")
    private String systemCode;

    /**
     * 按接口名称、地址或 operationId 搜索。
     */
    private String keyword;

    /**
     * 按 OpenAPI tag 过滤。
     */
    private String tag;

    /**
     * 当前页码。
     */
    private Integer current = 1;

    /**
     * 每页数量，避免一次返回全部接口。
     */
    private Integer size = 20;
}