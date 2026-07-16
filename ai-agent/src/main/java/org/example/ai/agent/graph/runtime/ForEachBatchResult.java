package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.ForEachItemStatus;

import java.util.List;

/**
 * FOREACH批量聚合结果。
 */
public record ForEachBatchResult(
        int totalCount,
        int successCount,
        int failureCount,
        int skippedCount,
        boolean partialSuccess,
        boolean allSucceeded,
        List<ForEachItemResult> items) {

    public ForEachBatchResult {
        items = items == null
                ? List.of()
                : List.copyOf(items);
    }

    public static ForEachBatchResult from(
            List<ForEachItemResult> items) {

        List<ForEachItemResult> safeItems =
                items == null
                        ? List.of()
                        : List.copyOf(items);

        int successCount =
                (int) safeItems.stream()
                        .filter(ForEachItemResult::isSuccess)
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

        boolean partialSuccess =
                successCount > 0
                        && (failureCount > 0
                        || skippedCount > 0);

        boolean allSucceeded =
                failureCount == 0
                        && skippedCount == 0;

        return new ForEachBatchResult(
                safeItems.size(),
                successCount,
                failureCount,
                skippedCount,
                partialSuccess,
                allSucceeded,
                safeItems
        );
    }
}