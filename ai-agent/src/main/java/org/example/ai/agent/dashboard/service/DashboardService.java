package org.example.ai.agent.dashboard.service;

import org.example.ai.agent.dashboard.vo.DashboardOverviewVO;

/**
 * 管理端首页系统总览服务。
 */
public interface DashboardService {

    /**
     * 查询最近指定天数的系统运行总览。
     */
    DashboardOverviewVO getOverview(int days);
}
