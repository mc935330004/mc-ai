package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.ForEachItemStatus;

import java.util.List;

/**
 * FOREACH批量聚合结果。
 *
 * successCount表示产生可用结果的数量，
 * 包含SUCCESS和PARTIAL_SUCCESS。
 *
 * partialCount是successCount的子集，
 * 不应再次累加到总数中。
 */
public record ForEachBatchResult(
        int totalCount,
        int successCount,
        int partialCount,
        int failureCount,
        int skippedCount,
        boolean partialSuccess,
        boolean allSucceeded,
        List<ForEachItemResult> items) {

    public ForEachBatchResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static ForEachBatchResult from(
            List<ForEachItemResult> items) {

        List<ForEachItemResult> safeItems = items == null
                        ? List.of()
                        : List.copyOf(items);

        /*
         * SUCCESS和PARTIAL_SUCCESS都包含可用结果。
         */
        int successCount =
                (int) safeItems.stream()
                        .filter(ForEachItemResult::isSuccess)
                        .count();

        int partialCount =
                (int) safeItems.stream()
                        .filter(item ->
                                item.status()
                                        == ForEachItemStatus.PARTIAL_SUCCESS
                        )
                        .count();

        int failureCount =
                (int) safeItems.stream()
                        .filter(item ->
                                item.status()
                                        == ForEachItemStatus.FAILED
                        )
                        .count();

        int skippedCount =
                (int) safeItems.stream()
                        .filter(item ->
                                item.status()
                                        == ForEachItemStatus.SKIPPED
                        )
                        .count();

        /*
         * 以下情况属于部分成功：
         * 1. 某个循环项本身为PARTIAL_SUCCESS；
         * 2. 有成功项，同时也有失败项；
         * 3. 存在主动跳过项。
         *
         * 全部失败不属于部分成功，而属于整体失败。
         */
        boolean partialSuccess =
                partialCount > 0
                        || skippedCount > 0
                        || (successCount > 0
                        && failureCount > 0);

        boolean allSucceeded =
                partialCount == 0
                        && failureCount == 0
                        && skippedCount == 0;

        return new ForEachBatchResult(
                safeItems.size(),
                successCount,
                partialCount,
                failureCount,
                skippedCount,
                partialSuccess,
                allSucceeded,
                safeItems
        );
    }
}