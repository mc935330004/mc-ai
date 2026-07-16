package org.example.ai.agent.workflow.run.model;

import org.example.ai.agent.common.enums.WorkflowRunOrigin;

import java.util.Map;

/**
 * 创建工作流运行记录。
 *
 * input必须是经过Schema清洗后的输入。
 */
public record WorkflowRunStartCommand(
        String runId,
        String agentRunId,
        String rootRunId,
        String sourceRunId,
        String requestId,
        Long workflowId,
        String workflowCode,
        String workflowName,
        Long workflowVersionId,
        Integer workflowVersionNo,
        Integer configRevision,
        String configChecksum,
        WorkflowRunOrigin origin,
        String userId,
        Map<String, Object> input) {

    public WorkflowRunStartCommand {
        input = input == null
                ? Map.of()
                : Map.copyOf(input);
    }
}