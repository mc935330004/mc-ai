package org.example.ai.agent.workflow.answer.chunk;

import java.util.List;

/**
 * 工作流回答分块覆盖率台账。
 *
 * 本阶段先使用内存对象传递；
 * P5企业治理阶段再考虑是否持久化到运行明细表。
 */
public record WorkflowAnswerChunkCoverage(
        int plannedChunks,
        int succeededChunks,
        int failedChunks,
        int pendingChunks,
        List<Integer> failedIndexes,
        List<WorkflowAnswerChunkAnalysis> analyses) {

    public WorkflowAnswerChunkCoverage {
        failedIndexes = failedIndexes == null
                ? List.of()
                : List.copyOf(failedIndexes);

        analyses = analyses == null
                ? List.of()
                : List.copyOf(analyses);
    }

    /**
     * 只有计划、成功、失败和未执行数量完全匹配时，
     * 才能认定所有分块已经被模型消费。
     */
    public boolean complete() {
        return plannedChunks > 0
                && succeededChunks == plannedChunks
                && failedChunks == 0
                && pendingChunks == 0
                && failedIndexes.isEmpty();
    }

    /**
     * 创建全部成功的覆盖率台账。
     */
    public static WorkflowAnswerChunkCoverage completed(
            WorkflowAnswerChunkPlan plan,
            List<WorkflowAnswerChunkAnalysis> analyses) {

        int succeeded =
                analyses == null
                        ? 0
                        : analyses.size();

        return new WorkflowAnswerChunkCoverage(
                plan.totalChunks(),
                succeeded,
                0,
                Math.max(
                        0,
                        plan.totalChunks() - succeeded
                ),
                List.of(),
                analyses
        );
    }

    /**
     * 创建分块调用失败时的覆盖率台账。
     */
    public static WorkflowAnswerChunkCoverage failed(
            WorkflowAnswerChunkPlan plan,
            List<WorkflowAnswerChunkAnalysis> analyses,
            int failedIndex) {

        int succeeded =analyses == null
                        ? 0
                        : analyses.size();

        int failed = 1;

        int pending =Math.max( 0,plan.totalChunks()- succeeded- failed);

        return new WorkflowAnswerChunkCoverage(
                plan.totalChunks(),
                succeeded,
                failed,
                pending,
                List.of(failedIndex),
                analyses
        );
    }
}