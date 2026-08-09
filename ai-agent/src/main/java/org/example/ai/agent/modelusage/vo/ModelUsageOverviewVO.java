package org.example.ai.agent.modelusage.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端模型调用监控汇总。
 */
@Data
public class ModelUsageOverviewVO {

    /**
     * 统计开始时间。
     */
    private LocalDateTime startTime;

    /**
     * 统计结束时间。
     */
    private LocalDateTime endTime;

    /**
     * 模型调用总次数。
     */
    private Long callCount;

    /**
     * 成功次数。
     */
    private Long successCount;

    /**
     * 失败次数。
     */
    private Long failureCount;

    /**
     * 成功率，取值范围为 0 至 100。
     */
    private BigDecimal successRate;

    /**
     * 累计 Token。
     */
    private Long totalTokens;

    /**
     * 平均耗时，单位毫秒。
     */
    private Long averageDurationMs;

    /**
     * 各模型调用统计。
     */
    private List<ModelUsageByModelVO> models;

    /**
     * 最近失败记录。
     */
    private List<RecentModelFailureVO> recentFailures;
}