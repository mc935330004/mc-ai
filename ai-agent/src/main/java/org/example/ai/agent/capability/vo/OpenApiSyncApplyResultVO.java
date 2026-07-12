package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

/**
 * OpenAPI同步执行结果。
 */
@Data
@Builder
public class OpenApiSyncApplyResultVO {

    private String systemCode;
    private Integer added;
    private Integer updated;
    private Integer disabled;
}