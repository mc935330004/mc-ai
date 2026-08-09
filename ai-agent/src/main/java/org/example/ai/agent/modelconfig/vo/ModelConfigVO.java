package org.example.ai.agent.modelconfig.vo;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置返回对象。
 *
 * 不包含密钥密文和密钥原文。
 */
@Data
@Builder
public class ModelConfigVO {

    private Long id;

    private String modelCode;

    private String displayName;

    private String providerCode;

    private String apiType;

    private String baseUrl;

    private Boolean apiKeyConfigured;

    private String apiKeyMasked;

    private String modelName;

    private BigDecimal temperature;

    private Integer maxTokens;

    private Integer timeoutSeconds;

    private Boolean streamingSupported;

    private Boolean structuredOutputSupported;

    private Boolean toolCallingSupported;

    private Integer contextWindow;

    private Boolean defaultModel;

    private Boolean enabled;

    private Integer sortOrder;

    private String remark;

    private Boolean lastTestSuccess;

    private String lastTestMessage;

    private Long lastTestDurationMs;

    private LocalDateTime lastTestAt;

    private String createdBy;

    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}