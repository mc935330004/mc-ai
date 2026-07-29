package org.example.ai.agent.alert.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.alert.entity.AlertRecord;
import org.example.ai.agent.alert.entity.AlertRule;
import org.example.ai.agent.alert.service.AlertService;
import org.example.ai.agent.alert.vo.AlertSummaryVO;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统告警中心接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/alerts")
public class AlertController {

    private static final String QUERY_PERMISSION = "ai_alert_query";

    private static final String HANDLE_PERMISSION = "ai_alert_handle";

    private static final String RULE_PERMISSION = "ai_alert_rule";

    private final AlertService alertService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 分页查询告警。
     */
    @GetMapping("/pageList")
    public Result<Page<AlertRecord>> pageList( Page<AlertRecord> page,
            @RequestParam( value = "status",required = false)String status,
            @RequestParam(value = "severity", required = false)String severity,
            @RequestParam(value = "workflowCode",required = false) String workflowCode,
            @RequestParam(value = "errorCode",required = false) String errorCode) {
        currentUserProvider.requirePermission(QUERY_PERMISSION);
        return Result.success(
                alertService.pageAlerts(
                        page,
                        status,
                        severity,
                        workflowCode,
                        errorCode
                )
        );
    }

    /**
     * 查询告警顶部汇总。
     */
    @GetMapping("/summary")
    public Result<AlertSummaryVO> summary() {
        currentUserProvider.requirePermission(QUERY_PERMISSION );

        return Result.success(alertService.summary() );
    }

    /**
     * 查询告警详情。
     */
    @GetMapping("/{alertId}")
    public Result<AlertRecord> detail(@PathVariable Long alertId) {
        currentUserProvider.requirePermission( QUERY_PERMISSION );
        return Result.success(alertService.detail( alertId ));
    }

    /**
     * 确认告警。
     */
    @PostMapping("/{alertId}/acknowledge")
    public Result<Void> acknowledge( @PathVariable Long alertId ) {
        currentUserProvider.requirePermission( HANDLE_PERMISSION);
        alertService.acknowledge(alertId,currentUserProvider.getRequiredUserId());
        return Result.success();
    }

    /**
     * 解决告警。
     */
    @PostMapping("/{alertId}/resolve")
    public Result<Void> resolve(@PathVariable Long alertId) {
        currentUserProvider.requirePermission(
                HANDLE_PERMISSION
        );

        alertService.resolve( alertId,
                currentUserProvider.getRequiredUserId());
        return Result.success();
    }

    /**
     * 查询所有告警规则。
     */
    @GetMapping("/rules")
    public Result<List<AlertRule>> rules() {
        currentUserProvider.requirePermission(RULE_PERMISSION);
        return Result.success(
                alertService.listRules()
        );
    }

    /**
     * 启用或停用告警规则。
     */
    @PutMapping("/rules/{ruleId}/enabled")
    public Result<Void> setRuleEnabled(@PathVariable Long ruleId, @RequestParam boolean enabled) {
        currentUserProvider.requirePermission(
                RULE_PERMISSION
        );
        alertService.setRuleEnabled(
                ruleId,
                enabled
        );
        return Result.success();
    }
}