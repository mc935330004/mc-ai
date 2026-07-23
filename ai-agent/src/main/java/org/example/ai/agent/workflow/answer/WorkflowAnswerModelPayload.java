package org.example.ai.agent.workflow.answer;

import org.example.ai.agent.workflow.runtime.WorkflowDescendantSummary;

import java.util.List;

/**
 * 发送给回答模型的精简工作流结果。
 *
 * 明确排除：
 * 1. runId；
 * 2. workflowCode；
 * 3. versionId；
 * 4. nodeId；
 * 5. 重复的批次items；
 * 6. visible=0字段。
 */
public record WorkflowAnswerModelPayload(
        boolean success,
        boolean partialSuccess,
        Object result,
        List<Batch> batches) {

    public WorkflowAnswerModelPayload {
        batches = batches == null ? List.of() : List.copyOf(batches);
    }

    /**
     * 只保留模型回答所需的批次统计。
     */
    public record Batch(
            int totalCount,
            int successCount,
            int partialCount,
            int failureCount,
            int skippedCount,
            boolean partialSuccess,
            WorkflowDescendantSummary descendants) {
    }
}