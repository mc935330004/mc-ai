package org.example.ai.agent.workflow.run.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.entity.WorkflowRunItem;
import org.example.ai.agent.workflow.run.model.WorkflowRunStartCommand;
import org.example.ai.agent.workflow.vo.WorkflowRunDetailVO;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.vo.WorkflowRunDetailVO;

import java.util.List;

public interface WorkflowRunService
        extends IService<WorkflowRun> {

    WorkflowRun start(
            WorkflowRunStartCommand command
    );

    void complete(
            String runId,
            WorkflowExecutionOutcome outcome
    );

    void markFailed(
            String runId,
            String errorCode,
            String errorMessage,
            long durationMs
    );

    Page<WorkflowRun> pageRuns(
            Page<WorkflowRun> page,
            String userId,
            String workflowCode,
            String status,
            String origin
    );

    WorkflowRunDetailVO detailOwned(
            String runId,
            String userId
    );

    WorkflowRun getRequiredOwned(
            String runId,
            String userId
    );

    List<WorkflowRunItem> listFailedItems(
            String runId,
            String nodeId
    );

    WorkflowRun findByRequestId(
            String requestId
    );

    WorkflowExecutionOutcome readOutcome(
            WorkflowRun run
    );
}