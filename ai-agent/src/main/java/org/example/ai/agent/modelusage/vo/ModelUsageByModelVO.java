package org.example.ai.agent.modelusage.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个模型调用统计。
 */
@Data
public class ModelUsageByModelVO {

    private String modelCode;

    private String provider;

    private String modelName;

    private Long callCount;

    private Long successCount;

    private Long failureCount;

    private BigDecimal successRate;

    private Long totalTokens;

    private Long averageDurationMs;
}