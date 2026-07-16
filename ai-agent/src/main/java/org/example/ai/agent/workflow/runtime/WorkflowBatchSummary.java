package org.example.ai.agent.workflow.runtime;

import org.example.ai.agent.graph.runtime.ForEachItemResult;

import java.util.List;

/**
 * 工作流FOREACH执行摘要。
 */
public record WorkflowBatchSummary(
        String nodeId,
        int totalCount,
        int successCount,
        int failureCount,
        int skippedCount,
        boolean partialSuccess,
        List<ForEachItemResult> items) {

    public WorkflowBatchSummary {
        items = items == null
                ? List.of()
                : List.copyOf(items);
    }
}