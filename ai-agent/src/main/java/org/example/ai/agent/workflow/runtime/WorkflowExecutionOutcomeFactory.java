package org.example.ai.agent.workflow.runtime;

import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.runtime.ForEachBatchResult;
import org.example.ai.agent.graph.runtime.GraphExecutionResult;
import org.example.ai.agent.graph.runtime.GraphNodeResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将内部 Graph 执行结果转换为对外安全的工作流结果。
 *
 * 主要职责：
 * 1. 不向大模型和前端暴露 Graph 内部变量池；
 * 2. 提取 FOREACH 批量执行摘要；
 * 3. 统一 success、partialSuccess 状态语义；
 * 4. 保证返回的 runId 不为空。
 */
@Component
public class WorkflowExecutionOutcomeFactory {

    /**
     * 创建工作流执行结果。
     *
     * @param runId        Facade 提交执行时生成的运行 ID
     * @param workflowCode 工作流编码
     * @param workflowName 工作流名称
     * @param versionId    工作流版本 ID
     * @param versionNo    工作流版本号
     * @param graphResult  Graph 执行结果
     */
    public WorkflowExecutionOutcome create(
            String runId,
            String workflowCode,
            String workflowName,
            Long versionId,
            Integer versionNo,
            GraphExecutionResult graphResult) {

        /*
         * 正常情况下 Graph 执行器不应该返回 null。
         * 这里保留防御性处理，避免空指针导致运行记录一直为 RUNNING。
         */
        if (graphResult == null) {
            return new WorkflowExecutionOutcome(
                    false,
                    false,
                    runId,
                    workflowCode,
                    workflowName,
                    versionId,
                    versionNo,
                    null,
                    "WORKFLOW_EXECUTION_EMPTY",
                    "工作流没有返回执行结果",
                    List.of(),
                    0L
            );
        }

        List<WorkflowBatchSummary> batches =
                extractBatches(graphResult);

        /*
         * partialSuccess 只能在工作流整体成功时成立。
         *
         * 如果 Graph 已经执行失败，即使某个 FOREACH 节点中有部分项目成功，
         * 工作流整体状态仍然应该是 FAILED，不能标记为 PARTIAL_SUCCESS。
         */
        boolean partialSuccess =
                graphResult.success()
                        && batches.stream()
                        .anyMatch(
                                WorkflowBatchSummary::partialSuccess
                        );

        /*
         * Graph 返回的 runId 理论上应与请求 runId 一致。
         * 如果 Graph 返回空值，则回退到 Facade 传入的 runId。
         */
        String actualRunId =
                StringUtils.hasText(graphResult.runId())
                        ? graphResult.runId()
                        : runId;

        return new WorkflowExecutionOutcome(
                graphResult.success(),
                partialSuccess,
                actualRunId,
                workflowCode,
                workflowName,
                versionId,
                versionNo,
                graphResult.result(),
                graphResult.errorCode(),
                graphResult.errorMessage(),
                batches,
                graphResult.durationMs()
        );
    }

    /**
     * 从所有节点结果中提取 FOREACH 批量执行结果。
     *
     * FOREACH 有两种情况：
     * 1. 节点成功：ForEachBatchResult 位于 nodeResult.data；
     * 2. 节点失败：ForEachBatchResult 位于 metadata.batchResult。
     */
    private List<WorkflowBatchSummary> extractBatches(
            GraphExecutionResult graphResult) {

        if (graphResult.nodeResults() == null
                || graphResult.nodeResults().isEmpty()) {

            return List.of();
        }

        List<WorkflowBatchSummary> summaries =
                new ArrayList<>();

        graphResult.nodeResults()
                .forEach((nodeId, nodeResult) -> {

                    ForEachBatchResult batch =
                            findBatch(nodeResult);

                    if (batch == null) {
                        return;
                    }

                    summaries.add(
                            new WorkflowBatchSummary(
                                    nodeId,
                                    batch.totalCount(),
                                    batch.successCount(),
                                    batch.partialCount(),
                                    batch.failureCount(),
                                    batch.skippedCount(),
                                    batch.partialSuccess(),
                                    /*
                                     * 递归统计项目下面的明细批次，
                                     * 但不把明细数量重复计入项目总数。
                                     */
                                    WorkflowDescendantSummary.from(
                                            batch
                                    ),
                                    batch.items()
                            )
                    );
                });

        return List.copyOf(summaries);
    }

    /**
     * 从FOREACH节点结果中提取批量执行结果。
     *
     * 重要说明：
     * END节点可能把FOREACH结果作为最终结果返回，
     * 但它不是真正的循环节点，不能再次计入批次统计，
     * 否则会造成项目数量和项目明细重复。
     */
    private ForEachBatchResult findBatch(GraphNodeResult nodeResult) {

        /*
         * 只允许真正的FOREACH节点产生批次摘要。
         *
         * 这项判断必须放在解析data和metadata之前，
         * 从源头排除END、MERGE等节点回传的重复批次。
         */
        if (nodeResult == null || nodeResult.nodeType() != GraphNodeType.FOREACH) {
            return null;
        }
        /*
         * FOREACH节点正常执行完成时，
         * 批量结果保存在data字段中。
         */
        if (nodeResult.data() instanceof ForEachBatchResult batch) {
            return batch;
        }
        /*
         * FOREACH因单项错误导致节点失败时，
         * 批量结果保存在metadata.batchResult中。
         */
        Map<String, Object> metadata = nodeResult.metadata();
        if (metadata != null && metadata.get("batchResult") instanceof ForEachBatchResult batch) {
            return batch;
        }
        return null;
    }
}