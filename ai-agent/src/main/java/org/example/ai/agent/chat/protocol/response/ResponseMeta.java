package org.example.ai.agent.chat.protocol.response;

/**
 * 一次回答的运行元数据。
 *
 * 用于前端状态展示、运行追踪和问题排查，
 * 不在这里保存完整业务数据。
 */
public record ResponseMeta(
        String workflowCode,
        String artifactId,
        String requestedModelCode,
        String effectiveModelCode,
        boolean fallbackUsed,
        long durationMs,
        long totalCount,
        long successCount,
        long failureCount,
        long skippedCount,
        boolean hasMore) {

    public ResponseMeta {
        workflowCode = ResponseSupport.normalizeText(workflowCode);
        artifactId = ResponseSupport.normalizeText(artifactId);
        requestedModelCode = ResponseSupport.normalizeText(requestedModelCode);
        effectiveModelCode = ResponseSupport.normalizeText(effectiveModelCode);

        durationMs = Math.max(durationMs, 0);
        totalCount = Math.max(totalCount, 0);
        successCount = Math.max(successCount, 0);
        failureCount = Math.max(failureCount, 0);
        skippedCount = Math.max(skippedCount, 0);
    }

    /**
     * 创建没有运行统计信息的默认元数据。
     */
    public static ResponseMeta empty() {
        return new ResponseMeta(
                "",
                "",
                "",
                "",
                false,
                0,
                0,
                0,
                0,
                0,
                false
        );
    }
}