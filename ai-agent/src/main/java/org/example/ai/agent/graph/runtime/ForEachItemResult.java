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

    /**
     * 因当前记录缺少必要字段而主动跳过。
     *
     * 与 GRAPH_FOREACH_ABORTED 不同：
     * 该状态不是由前一条记录失败导致，而是工作流明确配置的业务跳过规则。
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
                 * 原始列表记录继续保存在 item 字段中。
                 */
                null,
                code,
                message,
                durationMs
        );
    }
    /**
     * 前一条记录执行失败，并且 continueOnItemError=false 时，
     * 后续尚未执行的记录使用该跳过结果。
     *
     * 这是流程中止导致的跳过，不是因为当前记录缺少 id。
     */
    public static ForEachItemResult skipped(
            int index,
            Object item) {

        /*
         * 复用通用五参数方法，避免重复构造结果对象。
         */
        return skipped(
                index,
                item,
                "GRAPH_FOREACH_ABORTED",
                "前一条记录执行失败，当前记录未执行",
                0L
        );
    }
}