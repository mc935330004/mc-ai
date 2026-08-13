package org.example.ai.agent.dashboard.vo;

import lombok.Data;
import org.example.ai.agent.alert.vo.AlertSummaryVO;
import org.example.ai.agent.modelusage.vo.ModelUsageByModelVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端首页系统运行总览。
 */
@Data
public class DashboardOverviewVO {

    private LocalDateTime generatedAt;

    private PeriodStats period;

    private ResourceStats capability;

    private ResourceStats workflow;

    private RuntimeStats runtime;

    private ReportStats report;

    private ModelStats model;

    private KnowledgeStats knowledge;

    private AlertSummaryVO alert;

    private List<PopularQuestion> popularQuestions;

    private List<RecentIssue> recentIssues;

    /**
     * 当前统计周期。
     */
    @Data
    public static class PeriodStats {
        private Integer days;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    /**
     * 能力或工作流当前配置状态。
     */
    @Data
    public static class ResourceStats {
        private Long totalCount;
        private Long publishedCount;
        private Long enabledCount;
        private Long restrictedCount;
        private Long draftDirtyCount;
    }

    /**
     * Agent 业务运行统计。
     */
    @Data
    public static class RuntimeStats {
        private Long runCount;
        private Long successCount;
        private Long failedCount;
        private Long partialCount;
        private BigDecimal successRate;
        private Long averageDurationMs;
    }

    /**
     * 固定报告生成和分析状态统计。
     */
    @Data
    public static class ReportStats {
        private Long generatedCount;
        private Long dataQueryCount;
        private Long analysisReportCount;
        private Long analysisSuccessCount;
        private Long analysisFailedCount;
    }

    /**
     * 模型调用、Token 和人员用量统计。
     */
    @Data
    public static class ModelStats {
        private Long enabledCount;
        private Long callCount;
        private BigDecimal successRate;
        private Long failureCount;
        private Long totalTokens;
        private Long userTokenTotal;
        private Long averageDurationMs;
        private List<ModelUsageByModelVO> usageByModel;
        private List<UserTokenUsage> usageByUser;
    }

    /**
     * 按人员汇总的 Token 用量。
     *
     * userKey 返回页面使用的临时排行标识，displayName 返回脱敏账号。
     */
    @Data
    public static class UserTokenUsage {
        private String userKey;
        private String displayName;
        private String departmentName;
        private Long callCount;
        private Long totalTokens;
        private LocalDateTime lastUsedAt;
    }

    /**
     * 知识文档、问答和向量任务统计。
     */
    @Data
    public static class KnowledgeStats {
        private Long documentCount;
        private Long publishedDocumentCount;
        private Long queryCount;
        private BigDecimal querySuccessRate;
        private Long noResultCount;
        private Long failedCount;
        private Long vectorPendingCount;
        private Long vectorFailedCount;
    }

    /**
     * 归一化后的高频提问。
     */
    @Data
    public static class PopularQuestion {
        private String key;
        private String displayQuestion;
        private String topic;
        private Long questionCount;
        private Long userCount;
        private BigDecimal share;
        private LocalDateTime lastAskedAt;
    }

    /**
     * 首页最近异常安全摘要。
     */
    @Data
    public static class RecentIssue {
        private String id;
        private String sourceType;
        private String sourceName;
        private String severity;
        private String status;
        private String errorCode;
        private String errorMessage;
        private LocalDateTime occurredAt;
        private String targetPath;
    }
}
