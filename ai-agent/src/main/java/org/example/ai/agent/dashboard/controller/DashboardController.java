package org.example.ai.agent.dashboard.controller;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.dashboard.service.DashboardService;
import org.example.ai.agent.dashboard.vo.DashboardOverviewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端首页系统总览接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 查询系统主要功能和运行状态统计。
     */
    @GetMapping("/overview")
    public Result<DashboardOverviewVO> overview(
            @RequestParam(defaultValue = "7") int days) {
        return Result.success(dashboardService.getOverview(days));
    }
}
