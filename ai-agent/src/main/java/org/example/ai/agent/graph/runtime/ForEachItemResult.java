package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.ForEachItemStatus;

/**
 * FOREACH单项执行结果。
 */
public record ForEachItemResult(
        int index,
        Object item,
        ForEachItemStatus status,
        Object data,
        String errorCode,
        String errorMessage,
        long durationMs) {

    public boolean isSuccess() {
        return status == ForEachItemStatus.SUCCESS;
    }

    public static ForEachItemResult success(
            int index,
            Object item,
            Object data,
            long durationMs) {

        return new ForEachItemResult(
                index,
                item,
                ForEachItemStatus.SUCCESS,
                data,
                null,
                null,
                durationMs
        );
    }

    public static ForEachItemResult failure(
            int index,
            Object item,
            String errorCode,
            String errorMessage,
            long durationMs) {

        return new ForEachItemResult(
                index,
                item,
                ForEachItemStatus.FAILED,
                null,
                errorCode,
                errorMessage,
                durationMs
        );
    }

    public static ForEachItemResult skipped(
            int index,
            Object item) {

        return new ForEachItemResult(
                index,
                item,
                ForEachItemStatus.SKIPPED,
                null,
                "GRAPH_FOREACH_ABORTED",
                "前一项执行失败，当前项未执行",
                0
        );
    }
}