package org.example.ai.agent.workflow.runtime;

import org.example.ai.agent.graph.runtime.ForEachBatchResult;
import org.example.ai.agent.graph.runtime.ForEachItemResult;
import org.example.ai.agent.graph.runtime.ForEachNestedResultInspector;

import java.util.IdentityHashMap;

/**
 * 工作流嵌套批次统计。
 *
 * 外层批次一般表示用户输入的项目；
 * 后代批次一般表示业务接口返回的明细记录。
 *
 * 两类数量分开统计，避免把项目数和明细数相加造成重复计数。
 */
public record WorkflowDescendantSummary(
        int batchCount,
        int totalCount,
        int successCount,
        int partialCount,
        int failureCount,
        int skippedCount) {

    public static WorkflowDescendantSummary empty() {
        return new WorkflowDescendantSummary(
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    /**
     * 统计root批次下面的所有后代批次。
     *
     * root自身不计入后代统计。
     */
    public static WorkflowDescendantSummary from(
            ForEachBatchResult root) {

        if (root == null) {
            return empty();
        }

        Accumulator accumulator =
                new Accumulator();

        IdentityHashMap<ForEachBatchResult, Boolean> visited =
                new IdentityHashMap<>();

        /*
         * root只作为统计起点，不计入后代数量。
         */
        visited.put(root, Boolean.TRUE);

        collectChildren(
                root,
                accumulator,
                visited
        );

        return accumulator.toSummary();
    }

    private static void collectChildren(
            ForEachBatchResult parent,
            Accumulator accumulator,
            IdentityHashMap<ForEachBatchResult, Boolean> visited) {

        for (ForEachItemResult item : parent.items()) {
            ForEachNestedResultInspector
                    .findBatch(item.data())
                    .ifPresent(child ->
                            collectBatch(
                                    child,
                                    accumulator,
                                    visited
                            )
                    );
        }
    }

    private static void collectBatch(
            ForEachBatchResult batch,
            Accumulator accumulator,
            IdentityHashMap<ForEachBatchResult, Boolean> visited) {

        if (visited.put(batch, Boolean.TRUE) != null) {
            return;
        }

        accumulator.batchCount++;
        accumulator.totalCount +=
                batch.totalCount();
        accumulator.successCount +=
                batch.successCount();
        accumulator.partialCount +=
                batch.partialCount();
        accumulator.failureCount +=
                batch.failureCount();
        accumulator.skippedCount +=
                batch.skippedCount();

        collectChildren(
                batch,
                accumulator,
                visited
        );
    }

    /**
     * 仅用于当前统计过程，
     * 不向工作流结果直接暴露可变对象。
     */
    private static final class Accumulator {

        private int batchCount;
        private int totalCount;
        private int successCount;
        private int partialCount;
        private int failureCount;
        private int skippedCount;

        private WorkflowDescendantSummary toSummary() {
            return new WorkflowDescendantSummary(
                    batchCount,
                    totalCount,
                    successCount,
                    partialCount,
                    failureCount,
                    skippedCount
            );
        }
    }
}