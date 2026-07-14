package org.example.ai.agent.capability.evaluation.service;

import org.example.ai.agent.capability.evaluation.dto.CapabilityRouteEvalRequest;
import org.example.ai.agent.capability.evaluation.vo.CapabilityRouteEvalResultVO;

public interface CapabilityRouteEvaluationService {

    CapabilityRouteEvalResultVO run(CapabilityRouteEvalRequest request);
}