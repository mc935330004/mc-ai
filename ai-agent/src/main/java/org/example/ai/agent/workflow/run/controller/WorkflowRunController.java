package org.example.ai.agent.workflow.run.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.security.CurrentUserProvider;
import org.example.ai.agent.workflow.dto.WorkflowRetryFailedDTO;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.service.WorkflowRetryService;
import org.example.ai.agent.workflow.run.service.WorkflowRunService;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.vo.WorkflowRunDetailVO;
import org.springframework.web.bind.annotation.*;

/**
 * Workflow RunOps接口。
 *
 * 当前阶段用户只能查看和重试自己的运行记录。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/workflow-runs")
public class WorkflowRunController {

    private final WorkflowRunService runService;
    private final WorkflowRetryService retryService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping("/pageList")
    public Result<Page<WorkflowRun>> pageList(
            Page<WorkflowRun> page,
            @RequestParam(
                    value = "workflowCode",
                    required = false)
            String workflowCode,
            @RequestParam(
                    value = "status",
                    required = false)
            String status,
            @RequestParam(
                    value = "origin",
                    required = false)
            String origin) {

        return Result.success(
                runService.pageRuns(
                        page,
                        currentUserProvider
                                .getRequiredUserId(),
                        workflowCode,
                        status,
                        origin
                )
        );
    }

    @GetMapping("/detail/{runId}")
    public Result<WorkflowRunDetailVO> detail(
            @PathVariable String runId) {

        return Result.success(
                runService.detailOwned(
                        runId,
                        currentUserProvider
                                .getRequiredUserId()
                )
        );
    }

    @PostMapping("/{runId}/retry-failed")
    public Result<WorkflowExecutionOutcome>
    retryFailed(
            @PathVariable String runId,
            @RequestBody
            WorkflowRetryFailedDTO request) {

        return Result.success(
                retryService.retryFailed(
                        runId,
                        request,
                        currentUserProvider
                                .getRequiredUserId(),
                        currentUserProvider
                                .getRequiredAuthorization()
                )
        );
    }
}