package org.example.ai.agent.capability.evaluation.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.evaluation.dto.CapabilityRouteEvalRequest;
import org.example.ai.agent.capability.evaluation.dto.CapabilityRouteFeedbackDTO;
import org.example.ai.agent.capability.evaluation.entity.CapabilityRouteFeedback;
import org.example.ai.agent.capability.evaluation.service.CapabilityRouteEvaluationService;
import org.example.ai.agent.capability.evaluation.service.CapabilityRouteFeedbackService;
import org.example.ai.agent.capability.evaluation.vo.CapabilityRouteEvalResultVO;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/route-evaluation")
public class CapabilityRouteEvaluationController {

    private final CapabilityRouteFeedbackService feedbackService;
    private final CapabilityRouteEvaluationService evaluationService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 提交路由结果反馈。
     */
    @PostMapping("/feedback")
    public Result<CapabilityRouteFeedback> submitFeedback( @RequestBody CapabilityRouteFeedbackDTO dto) {
        return Result.success(feedbackService.submit(dto,currentUserProvider .getRequiredUserId()));
    }

    /**
     * 审核通过反馈。
     *
     * 生产环境应限制为管理员或AI运营角色。
     */
    @PostMapping("/feedback/{id}/approve")
    public Result<Void> approveFeedback(@PathVariable Long id) {
        feedbackService.approve(id, currentUserProvider.getRequiredUserId());
        return Result.success();
    }

    /**
     * 驳回反馈。
     */
    @PostMapping("/feedback/{id}/reject")
    public Result<Void> rejectFeedback( @PathVariable Long id) {
        feedbackService.reject(id,currentUserProvider.getRequiredUserId());
        return Result.success();
    }

    /**
     * 执行离线路由评测。
     *
     * 只运行 Router 和 Planner，
     * 不执行 ToolExecutor，不调用真实业务接口。
     */
    @PostMapping("/run")
    public Result<CapabilityRouteEvalResultVO> runEvaluation(
            @RequestBody(required = false)CapabilityRouteEvalRequest request) {

        return Result.success( evaluationService.run(request));
    }
}