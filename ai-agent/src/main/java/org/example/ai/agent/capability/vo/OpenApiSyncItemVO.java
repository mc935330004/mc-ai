package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 单个OpenAPI接口变化。
 */
@Data
@Builder
public class OpenApiSyncItemVO {

    /**
     * ADDED、REMOVED、CHANGED、UNCHANGED。
     */
    private String changeType;

    private String operationId;
    private String capabilityCode;
    private String capabilityName;

    private String oldMethod;
    private String newMethod;

    private String oldUrl;
    private String newUrl;

    private Boolean inputSchemaChanged;
    private Boolean outputSchemaChanged;
}