package org.example.ai.agent.capability.evaluation.service;

import org.example.ai.agent.capability.evaluation.dto.CapabilityRouteEvalRequest;
import org.example.ai.agent.capability.evaluation.vo.CapabilityRouteEvalResultVO;

public interface CapabilityRouteEvaluationService {

    /**
     * 使用当前管理员的真实人员编码执行离线路由评测。
     */
    CapabilityRouteEvalResultVO run(
            CapabilityRouteEvalRequest request,
            String userId);
}
