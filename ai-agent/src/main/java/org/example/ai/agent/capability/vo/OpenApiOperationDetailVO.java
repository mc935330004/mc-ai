package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

/**
 * OpenAPI 单接口完整结构。
 */
@Data
@Builder
public class OpenApiOperationDetailVO {

    private String operationId;
    private String capabilityCode;
    private String capabilityName;
    private String method;
    private String url;
    private String sideEffect;
    private Boolean requireConfirm;

    /**
     * 只有详情接口才返回完整 Schema。
     */
    private String inputSchemaJson;
    private String outputSchemaJson;
    /**
     * OpenAPI 接口说明。
     */
    private String description;

    /**
     * OpenAPI 接口分组。
     */
    private String tag;
}