package org.example.ai.agent.workflow.runtime;

import org.example.ai.agent.graph.runtime.ForEachItemResult;

import java.util.List;

/**
 * 工作流顶层FOREACH执行摘要。
 *
 * 顶层通常表示用户输入的项目，
 * descendants表示项目下面的业务明细。
 */
public record WorkflowBatchSummary(
        String nodeId,
        int totalCount,
        int successCount,
        int partialCount,
        int failureCount,
        int skippedCount,
        boolean partialSuccess,
        WorkflowDescendantSummary descendants,
        List<ForEachItemResult> items) {

    public WorkflowBatchSummary {
        descendants = descendants == null
                ? WorkflowDescendantSummary.empty()
                : descendants;

        items = items == null
                ? List.of()
                : List.copyOf(items);
    }
}