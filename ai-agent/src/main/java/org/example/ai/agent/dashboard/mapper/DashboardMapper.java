package org.example.ai.agent.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.dashboard.vo.DashboardOverviewVO;
import org.example.ai.agent.modelusage.vo.ModelUsageByModelVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端首页统计数据访问接口。
 */
@Mapper
public interface DashboardMapper {

    DashboardOverviewVO.ResourceStats selectCapabilityStats();

    DashboardOverviewVO.ResourceStats selectWorkflowStats();

    DashboardOverviewVO.RuntimeStats selectRuntimeStats(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    DashboardOverviewVO.ReportStats selectReportStats(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    Long selectEnabledModelCount();

    DashboardOverviewVO.ModelStats selectModelStats(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    List<ModelUsageByModelVO> selectModelUsageByModel(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    Long selectUserTokenTotal(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    List<DashboardOverviewVO.UserTokenUsage> selectUserTokenUsage(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit
    );

    DashboardOverviewVO.KnowledgeStats selectKnowledgeStats(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    Long selectQuestionCount(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    List<DashboardOverviewVO.PopularQuestion> selectPopularQuestions(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit
    );

    List<DashboardOverviewVO.RecentIssue> selectRecentIssues(
            @Param("tenantId") Long tenantId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit
    );
}
