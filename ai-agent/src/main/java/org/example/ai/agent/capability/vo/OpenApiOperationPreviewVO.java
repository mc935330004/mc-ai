package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

/**
 * OpenAPI 接口对应的能力候选项。
 */
@Data
@Builder
public class OpenApiOperationPreviewVO {

    private String operationId;

    private String capabilityCode;

    private String capabilityName;

    private String description;

    private String tag;

    private String method;

    private String url;

    /**
     * READ、WRITE 或 DANGEROUS。
     */
    private String sideEffect;

    private Boolean requireConfirm;
    /**
     * 是否存在输入参数。
     */
    private Boolean hasInputSchema;

    /**
     * 是否存在响应结构。
     */
    private Boolean hasOutputSchema;

}