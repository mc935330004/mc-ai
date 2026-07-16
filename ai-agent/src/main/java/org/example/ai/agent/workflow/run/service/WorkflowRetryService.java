package org.example.ai.agent.workflow.run.service;

import org.example.ai.agent.workflow.dto.WorkflowRetryFailedDTO;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;

public interface WorkflowRetryService {

    WorkflowExecutionOutcome retryFailed(
            String sourceRunId,
            WorkflowRetryFailedDTO request,
            String userId,
            String authorization
    );
}