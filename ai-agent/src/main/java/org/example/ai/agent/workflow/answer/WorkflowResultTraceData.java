package org.example.ai.agent.workflow.answer;

import org.example.ai.agent.workflow.runtime.WorkflowBatchSummary;
import org.example.ai.agent.workflow.runtime.WorkflowDescendantSummary;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;

import java.util.List;
import java.util.Locale;

/**
 * 工作流业务结果安全遥测。
 *
 * 只记录数量、状态和预期展示类型：
 * 1. 不保存项目名称、金额、人员等业务数据；
 * 2. 不保存工作流完整结果；
 * 3. 用于判断数据是在查询、回答还是前端展示阶段丢失。
 */
public record WorkflowResultTraceData(

        /**
         * 后端期望前端采用的展示类型。
         *
         * P6-0阶段只记录期望值；
         * P6-2阶段再正式加入前后端消息协议。
         */
        String expectedPresentationType,

        /**
         * 工作流是否成功执行。
         */
        boolean workflowSuccess,

        /**
         * 是否存在部分成功。
         */
        boolean partialSuccess,

        /**
         * 工作流是否返回了结果对象。
         */
        boolean resultPresent,

        /**
         * 顶层批次数量。
         */
        int batchCount,

        /**
         * 顶层业务对象总数，例如用户输入的项目数量。
         */
        long topLevelTotalCount,

        /**
         * 顶层成功数量。
         *
         * 注意：当前successCount已经包含PARTIAL_SUCCESS，
         * 不能再把partialCount累加进来。
         */
        long topLevelSuccessCount,

        /**
         * 顶层部分成功数量，它是successCount的子集。
         */
        long topLevelPartialCount,

        /**
         * 顶层失败数量。
         */
        long topLevelFailureCount,

        /**
         * 顶层跳过数量。
         */
        long topLevelSkippedCount,

        /**
         * 后代批次数量，例如项目下面的结算记录批次。
         */
        long descendantBatchCount,

        /**
         * 后代业务记录总数，例如全部结算明细数量。
         */
        long descendantTotalCount,

        /**
         * 后代成功数量。
         */
        long descendantSuccessCount,

        /**
         * 后代部分成功数量，它是successCount的子集。
         */
        long descendantPartialCount,

        /**
         * 后代失败数量。
         */
        long descendantFailureCount,
        /**
         * 后代跳过数量，例如没有id而跳过详情查询的记录。
         */
        long descendantSkippedCount,
        /**
         * 工作流结果是否满足完整回答条件。
         *
         * 这里只代表工作流执行结果完整，
         * 不代表模型最终文本一定保留了全部明细。
         */
        boolean workflowDataComplete) {

    /**
     * 从工作流结果生成安全遥测。
     */
    public static WorkflowResultTraceData from(
            WorkflowExecutionOutcome outcome,
            String expectedPresentationType) {

        if (outcome == null) {
            return empty(expectedPresentationType);
        }

        List<WorkflowBatchSummary> batches =
                outcome.batches() == null
                        ? List.of()
                        : outcome.batches();

        long topLevelTotalCount = 0L;
        long topLevelSuccessCount = 0L;
        long topLevelPartialCount = 0L;
        long topLevelFailureCount = 0L;
        long topLevelSkippedCount = 0L;

        long descendantBatchCount = 0L;
        long descendantTotalCount = 0L;
        long descendantSuccessCount = 0L;
        long descendantPartialCount = 0L;
        long descendantFailureCount = 0L;
        long descendantSkippedCount = 0L;

        for (WorkflowBatchSummary batch : batches) {
            if (batch == null) {
                continue;
            }

            topLevelTotalCount += batch.totalCount();
            topLevelSuccessCount += batch.successCount();
            topLevelPartialCount += batch.partialCount();
            topLevelFailureCount += batch.failureCount();
            topLevelSkippedCount += batch.skippedCount();

            WorkflowDescendantSummary descendants =
                    batch.descendants();

            if (descendants == null) {
                continue;
            }

            descendantBatchCount += descendants.batchCount();
            descendantTotalCount += descendants.totalCount();
            descendantSuccessCount += descendants.successCount();
            descendantPartialCount += descendants.partialCount();
            descendantFailureCount += descendants.failureCount();
            descendantSkippedCount += descendants.skippedCount();
        }

        boolean workflowDataComplete =
                outcome.success()
                        && !outcome.partialSuccess()
                        && topLevelFailureCount == 0
                        && topLevelSkippedCount == 0
                        && descendantFailureCount == 0
                        && descendantSkippedCount == 0;

        return new WorkflowResultTraceData(
                normalizePresentationType(expectedPresentationType),
                outcome.success(),
                outcome.partialSuccess(),
                outcome.result() != null,
                batches.size(),
                topLevelTotalCount,
                topLevelSuccessCount,
                topLevelPartialCount,
                topLevelFailureCount,
                topLevelSkippedCount,
                descendantBatchCount,
                descendantTotalCount,
                descendantSuccessCount,
                descendantPartialCount,
                descendantFailureCount,
                descendantSkippedCount,
                workflowDataComplete
        );
    }

    private static WorkflowResultTraceData empty(
            String expectedPresentationType) {

        return new WorkflowResultTraceData(
                normalizePresentationType(expectedPresentationType),
                false,
                false,
                false,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                false
        );
    }

    private static String normalizePresentationType(String value) {
        return value == null || value.isBlank()
                ? "MARKDOWN"
                : value.trim().toUpperCase(Locale.ROOT);
    }
}