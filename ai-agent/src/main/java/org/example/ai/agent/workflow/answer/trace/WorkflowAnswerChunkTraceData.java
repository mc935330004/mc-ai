package org.example.ai.agent.workflow.answer.trace;

/**
 * 大模型分块消费安全遥测。
 *
 * 只记录计数、字符数和覆盖率，
 * 不保存业务数据、分块内容、模型摘要或提示词。
 */
public record WorkflowAnswerChunkTraceData(
        int plannedChunks,
        int succeededChunks,
        int failedChunks,
        int pendingChunks,
        int modelCalls,
        int sourceCharCount,
        int chunkCharCount,
        boolean complete) {
}