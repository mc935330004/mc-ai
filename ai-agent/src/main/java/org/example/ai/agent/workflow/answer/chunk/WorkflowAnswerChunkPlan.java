package org.example.ai.agent.workflow.answer.chunk;

import java.util.List;

/**
 * 一次工作流结果的完整分块计划。
 *
 * @param totalChunks      分块总数
 * @param sourceCharCount  原始安全结果字符数
 * @param chunkCharCount   所有分块字符数之和
 * @param chunks           全部分块，不允许缺失
 */
public record WorkflowAnswerChunkPlan(
        int totalChunks,
        int sourceCharCount,
        int chunkCharCount,
        List<WorkflowAnswerChunk> chunks) {

    public WorkflowAnswerChunkPlan {
        chunks = chunks == null? List.of(): List.copyOf(chunks);
    }
}