package org.example.ai.agent.workflow.answer.chunk;

/**
 * 大模型对单个工作流数据块的分析结果。
 *
 * @param chunkIndex   分块序号
 * @param sourcePointer 原始数据位置
 * @param sourceSha256 原始数据块摘要
 * @param sourceChars  原始数据块字符数
 * @param summary      模型生成的分块业务摘要
 */
public record WorkflowAnswerChunkAnalysis(
        int chunkIndex,
        String sourcePointer,
        String sourceSha256,
        int sourceChars,
        String summary) {
}