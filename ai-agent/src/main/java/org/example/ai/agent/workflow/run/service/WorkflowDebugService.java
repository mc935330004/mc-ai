package org.example.ai.agent.workflow.run.service;

import org.example.ai.agent.workflow.dto.WorkflowDebugRequestDTO;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;

public interface WorkflowDebugService {

    WorkflowExecutionOutcome debug(
            Long workflowId,
            WorkflowDebugRequestDTO request,
            String userId,
            String authorization
    );
}