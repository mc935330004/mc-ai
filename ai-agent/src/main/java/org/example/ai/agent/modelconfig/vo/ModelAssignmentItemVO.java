package org.example.ai.agent.modelconfig.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 单个授权模型返回对象。
 */
@Data
@Builder
public class ModelAssignmentItemVO {

    private String modelCode;

    private String displayName;

    private String providerCode;

    private Boolean enabled;

    private Boolean defaultModel;

    private Integer fallbackPriority;
}