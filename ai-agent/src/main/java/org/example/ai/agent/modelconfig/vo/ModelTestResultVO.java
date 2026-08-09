package org.example.ai.agent.modelconfig.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型连接测试结果。
 */
@Data
@Builder
public class ModelTestResultVO {

    private String modelCode;

    private Boolean success;

    private String provider;

    private String modelName;

    private Long durationMs;

    private String responseText;

    private String errorCategory;

    private String message;

    private LocalDateTime testedAt;
}