package org.example.ai.agent.workflow.answer.trace;

/**
 * 大模型分层汇总安全遥测。
 */
public record WorkflowAnswerReductionTraceData(
        int reductionLevels,
        int modelCalls,
        int coveredChunks,
        boolean complete) {
}