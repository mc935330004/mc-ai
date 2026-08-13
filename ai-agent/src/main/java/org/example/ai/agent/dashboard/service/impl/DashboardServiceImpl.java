package org.example.ai.agent.dashboard.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.alert.service.AlertService;
import org.example.ai.agent.alert.vo.AlertSummaryVO;
import org.example.ai.agent.dashboard.mapper.DashboardMapper;
import org.example.ai.agent.dashboard.service.DashboardService;
import org.example.ai.agent.dashboard.vo.DashboardOverviewVO;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端首页系统总览服务实现。
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 90;
    private static final int RANKING_LIMIT = 5;
    private static final int ISSUE_LIMIT = 10;

    private final DashboardMapper dashboardMapper;
    private final AlertService alertService;
    private final KnowledgeAccessContext knowledgeAccessContext;

    /**
     * 首页只读取已经持久化的统计数据，不在请求过程中调用模型或业务系统。
     */
    @Override
    @Transactional(readOnly = true)
    public DashboardOverviewVO getOverview(int days) {
        int normalizedDays = Math.max(MIN_DAYS, Math.min(days, MAX_DAYS));
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(normalizedDays);
        KnowledgeAccessPrincipal principal = knowledgeAccessContext.getRequiredPrincipal();

        DashboardOverviewVO overview = new DashboardOverviewVO();
        overview.setGeneratedAt(endTime);
        overview.setPeriod(buildPeriod(normalizedDays, startTime, endTime));
        overview.setCapability(dashboardMapper.selectCapabilityStats());
        overview.setWorkflow(dashboardMapper.selectWorkflowStats());
        overview.setRuntime(dashboardMapper.selectRuntimeStats(
                principal.tenantId(),
                startTime,
                endTime
        ));
        overview.setReport(dashboardMapper.selectReportStats(
                principal.tenantId(),
                startTime,
                endTime
        ));
        overview.setModel(buildModelStats(principal.tenantId(), startTime, endTime));
        overview.setKnowledge(dashboardMapper.selectKnowledgeStats(
                principal.tenantId(),
                startTime,
                endTime
        ));
        overview.setAlert(safeAlertSummary(alertService.summary()));
        overview.setPopularQuestions(buildPopularQuestions(
                principal.tenantId(),
                startTime,
                endTime
        ));
        overview.setRecentIssues(dashboardMapper.selectRecentIssues(
                principal.tenantId(),
                startTime,
                endTime,
                ISSUE_LIMIT
        ));
        return overview;
    }

    private DashboardOverviewVO.PeriodStats buildPeriod(
            int days,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        DashboardOverviewVO.PeriodStats period = new DashboardOverviewVO.PeriodStats();
        period.setDays(days);
        period.setStartTime(startTime);
        period.setEndTime(endTime);
        return period;
    }

    private DashboardOverviewVO.ModelStats buildModelStats(
            Long tenantId,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        List<DashboardOverviewVO.UserTokenUsage> users = dashboardMapper.selectUserTokenUsage(
                tenantId,
                startTime,
                endTime,
                RANKING_LIMIT
        );
        users = users == null ? List.of() : users;
        for (int index = 0; index < users.size(); index++) {
            protectUserIdentity(users.get(index), index);
        }

        DashboardOverviewVO.ModelStats model = dashboardMapper.selectModelStats(
                tenantId,
                startTime,
                endTime
        );
        if (model == null) {
            model = new DashboardOverviewVO.ModelStats();
        }
        model.setEnabledCount(safeLong(dashboardMapper.selectEnabledModelCount()));
        model.setCallCount(safeLong(model.getCallCount()));
        model.setSuccessRate(safeDecimal(model.getSuccessRate()));
        model.setFailureCount(safeLong(model.getFailureCount()));
        model.setTotalTokens(safeLong(model.getTotalTokens()));
        model.setUserTokenTotal(safeLong(dashboardMapper.selectUserTokenTotal(
                tenantId,
                startTime,
                endTime
        )));
        model.setAverageDurationMs(safeLong(model.getAverageDurationMs()));
        model.setUsageByModel(dashboardMapper.selectModelUsageByModel(
                tenantId,
                startTime,
                endTime
        ));
        if (model.getUsageByModel() == null) {
            model.setUsageByModel(List.of());
        }
        model.setUsageByUser(users);
        return model;
    }

    private List<DashboardOverviewVO.PopularQuestion> buildPopularQuestions(
            Long tenantId,
            LocalDateTime startTime,
            LocalDateTime endTime) {
        long questionTotal = safeLong(dashboardMapper.selectQuestionCount(
                tenantId,
                startTime,
                endTime
        ));
        List<DashboardOverviewVO.PopularQuestion> questions = dashboardMapper.selectPopularQuestions(
                tenantId,
                startTime,
                endTime,
                RANKING_LIMIT
        );
        if (questions == null || questions.isEmpty()) {
            return List.of();
        }
        for (int index = 0; index < questions.size(); index++) {
            protectQuestion(questions.get(index), questionTotal, index);
        }
        return questions;
    }

    /**
     * 首页不返回可直接用于登录或调用业务接口的真实账号。
     */
    private void protectUserIdentity(
            DashboardOverviewVO.UserTokenUsage usage,
            int index) {
        String userId = usage.getUserKey();
        usage.setUserKey("USER-" + (index + 1));
        usage.setDisplayName(maskUserId(userId));
        usage.setDepartmentName("");
    }

    /**
     * 问题分组键只用于前端稳定渲染，返回前转换为临时排行标识。
     */
    private void protectQuestion(
            DashboardOverviewVO.PopularQuestion question,
            long questionTotal,
            int index) {
        question.setKey("QUESTION-" + (index + 1));
        question.setDisplayQuestion(limit(question.getDisplayQuestion(), 80));
        BigDecimal share = questionTotal <= 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(safeLong(question.getQuestionCount()))
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(questionTotal), 2, RoundingMode.HALF_UP);
        question.setShare(share);
    }

    private AlertSummaryVO safeAlertSummary(AlertSummaryVO summary) {
        if (summary != null) {
            return summary;
        }
        AlertSummaryVO empty = new AlertSummaryVO();
        empty.setActiveCount(0L);
        empty.setOpenCount(0L);
        empty.setAcknowledgedCount(0L);
        empty.setCriticalCount(0L);
        empty.setErrorCount(0L);
        empty.setWarningCount(0L);
        return empty;
    }

    private String maskUserId(String value) {
        if (!StringUtils.hasText(value)) {
            return "未识别用户";
        }
        String userId = value.trim();
        if (userId.length() == 1) {
            return userId + "*";
        }
        if (userId.length() == 2) {
            return userId.charAt(0) + "*";
        }
        return userId.charAt(0) + "***" + userId.charAt(userId.length() - 1);
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "未命名问题";
        }
        String text = value.trim();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private long safeLong(Long value) {
        return value == null ? 0L : Math.max(value, 0L);
    }

    private BigDecimal safeDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.max(BigDecimal.ZERO);
    }
}
