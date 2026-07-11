package org.example.ai.agent.pending.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.pending.entity.PendingAction;
import org.example.ai.agent.plan.DynamicCapabilityPlan;

/**
 * 待确认操作服务。
 */
public interface PendingActionService extends IService<PendingAction> {

    /**
     * 根据写操作计划创建待确认记录。
     */
    PendingAction createPendingAction( String runId, String userId,DynamicCapabilityPlan plan);

    /**
     * 查询当前用户的待确认操作，并自动处理过期状态。
     */
    PendingAction getAction(String runId, String userId);

    /**
     * 取消仍处于 PENDING 状态的操作。
     */
    PendingAction cancelAction(String runId, String userId);

    /**
     * 确认待执行操作。
     *
     * 本方法只改变状态，不调用业务系统。
     */
    PendingAction confirmAction(String runId, String userId);

    /**
     * 执行已经确认的写操作。
     */
    PendingAction executeConfirmedAction(String runId, String userId,String authorization);

    /**
     * 确认并执行写操作。
     *
     * 重复调用成功操作时，直接返回第一次执行结果。
     */
    PendingAction confirmAndExecuteAction(String runId, String userId,String authorization);
}