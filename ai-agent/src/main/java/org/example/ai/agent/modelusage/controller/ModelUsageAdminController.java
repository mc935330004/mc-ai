package org.example.ai.agent.modelusage.controller;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.modelusage.vo.ModelUsageOverviewVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端模型调用监控接口。
 *
 * 继续复用现有 Agent 管理端权限范围。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/model-usage")
public class ModelUsageAdminController {

    private final ModelUsageService modelUsageService;

    /**
     * 查询模型调用汇总、模型统计和最近失败。
     */
    @GetMapping("/overview")
    public Result<ModelUsageOverviewVO> overview(
            @RequestParam(
                    defaultValue = "7"
            ) int days,
            @RequestParam(
                    defaultValue = "1"
            ) long failureCurrent,
            @RequestParam(
                    defaultValue = "10"
            ) long failureSize) {

        return Result.success(
                modelUsageService.getOverview(
                        days,
                        failureCurrent,
                        failureSize
                )
        );
    }
}
