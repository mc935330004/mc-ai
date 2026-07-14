package org.example.ai.agent.capability.evaluation.service;

import org.example.ai.agent.capability.routing.CapabilityCandidate;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.plan.DynamicCapabilityPlan;

import java.util.List;

public interface CapabilityRouteAuditService {

    /**
     * 保存一次正常能力规划结果。
     *
     * 审计失败不能影响主业务。
     */
    void recordDecision( ModelCallContext context,
            String userQuestion,
            List<CapabilityCandidate> candidates,
            DynamicCapabilityPlan plan,
            long durationMs);

    /**
     * 保存能力规划异常。
     */
    void recordFailure( ModelCallContext context, String userQuestion, List<CapabilityCandidate> candidates,
            String errorMessage,
            long durationMs );
}