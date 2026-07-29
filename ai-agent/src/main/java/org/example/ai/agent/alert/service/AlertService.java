package org.example.ai.agent.alert.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.alert.entity.AlertRecord;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.ai.agent.alert.entity.AlertRule;
import org.example.ai.agent.alert.vo.AlertSummaryVO;

import java.util.List;
/**
 * 系统告警服务。
 */
public interface AlertService
        extends IService<AlertRecord> {

    /**
     * 根据失败的工作流运行记录生成或累加告警。
     *
     * 非FAILED状态会被直接忽略。
     */
    void recordWorkflowFailure(WorkflowRun run);

    /**
     * 确认一条待处理告警。
     */
    void acknowledge(Long alertId, String operatorId);

    /**
     * 将活动告警标记为已解决。
     */
    void resolve(Long alertId,String operatorId);
    /**
     * 分页查询告警。
     */
    Page<AlertRecord> pageAlerts(Page<AlertRecord> page, String status, String severity,String workflowCode,String errorCode);

    /**
     * 查询告警详情。
     */
    AlertRecord detail(Long alertId);

    /**
     * 查询告警数量汇总。
     */
    AlertSummaryVO summary();

    /**
     * 查询所有告警规则。
     */
    List<AlertRule> listRules();

    /**
     * 启用或停用告警规则。
     */
    void setRuleEnabled(Long ruleId,boolean enabled );
}