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

    /**
     * 判断当前循环项是否产生了可用结果。
     *
     * PARTIAL_SUCCESS虽然不代表完全成功，
     * 但仍然包含列表数据或部分详情数据。
     */
    public boolean isSuccess() {
        return status == ForEachItemStatus.SUCCESS || status == ForEachItemStatus.PARTIAL_SUCCESS;
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

    /**
     * 当前循环项有可用结果，
     * 但嵌套子流程中存在失败或跳过记录。
     */
    public static ForEachItemResult partialSuccess(
            int index,
            Object item,
            Object data,
            long durationMs) {

        return new ForEachItemResult(
                index,
                item,
                ForEachItemStatus.PARTIAL_SUCCESS,
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

    /**
     * 根据子图结果创建FOREACH单项结果。
     *
     * 该方法负责将内层FOREACH状态传播到外层项目：
     * 1. 子图失败：FAILED；
     * 2. 子图成功但内层存在失败或跳过：PARTIAL_SUCCESS；
     * 3. 子图及内层全部成功：SUCCESS。
     */
    public static ForEachItemResult fromChildResult(
            int index,
            Object item,
            GraphExecutionResult childResult,
            long durationMs) {

        if (childResult == null) {
            return failure(
                    index,
                    item,
                    "GRAPH_FOREACH_CHILD_EMPTY",
                    "循环项子图没有返回执行结果",
                    durationMs
            );
        }

        if (!childResult.success()) {
            return failure(
                    index,
                    item,
                    safeErrorCode(
                            childResult.errorCode()
                    ),
                    safeErrorMessage(
                            childResult.errorMessage()
                    ),
                    durationMs
            );
        }

        Object childData =
                childResult.result();

        boolean containsIncompleteBatch =
                ForEachNestedResultInspector
                        .findBatch(childData)
                        .map(batch ->
                                !batch.allSucceeded()
                        )
                        .orElse(false);

        if (containsIncompleteBatch) {
            return partialSuccess(
                    index,
                    item,
                    childData,
                    durationMs
            );
        }

        return success(
                index,
                item,
                childData,
                durationMs
        );
    }

    /**
     * 因当前记录缺少必要字段而主动跳过。
     */
    public static ForEachItemResult skipped(
            int index,
            Object item,
            String code,
            String message,
            long durationMs) {

        return new ForEachItemResult(
                index,
                item,
                ForEachItemStatus.SKIPPED,
                /*
                 * 跳过时没有详情数据。
                 * 原始列表记录继续保留在item字段中。
                 */
                null,
                code,
                message,
                durationMs
        );
    }

    /**
     * 前一条记录失败且continueOnItemError=false时，
     * 后续尚未执行的记录使用该结果。
     */
    public static ForEachItemResult skipped(
            int index,
            Object item) {

        return skipped(
                index,
                item,
                "GRAPH_FOREACH_ABORTED",
                "前一条记录执行失败，当前记录未执行",
                0L
        );
    }

    private static String safeErrorCode(
            String errorCode) {

        return errorCode == null
                || errorCode.isBlank()
                ? "GRAPH_FOREACH_CHILD_FAILED"
                : errorCode;
    }

    private static String safeErrorMessage(
            String errorMessage) {

        return errorMessage == null
                || errorMessage.isBlank()
                ? "循环项子图执行失败"
                : errorMessage;
    }
}