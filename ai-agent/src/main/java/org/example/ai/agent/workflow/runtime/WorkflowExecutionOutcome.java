package org.example.ai.agent.workflow.runtime;

import java.util.List;

/**
 * 可安全交给前端和回答模型的工作流结果。
 *
 * 不包含：
 * 1. Authorization；
 * 2. secureContext；
 * 3. Graph变量池；
 * 4. 原始业务响应；
 * 5. 全部内部节点配置。
 */
public record WorkflowExecutionOutcome(
        boolean success,
        boolean partialSuccess,
        String runId,
        String workflowCode,
        String workflowName,
        Long versionId,
        Integer versionNo,
        Object result,
        String errorCode,
        String errorMessage,
        List<WorkflowBatchSummary> batches,
        long durationMs) {

    public WorkflowExecutionOutcome {
        batches = batches == null
                ? List.of()
                : List.copyOf(batches);
    }
}