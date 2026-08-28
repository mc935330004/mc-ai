package org.example.ai.agent.workflow.answer;

import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;

/**
 * 工作流回答基础数据准备结果。
 *
 * internalPayload供内部事实、风险规则和公式使用；
 * modelPayload只包含允许发送给模型的数据。
 */
public record WorkflowAnswerPreparation(
        WorkflowExecutionOutcome outcome,
        WorkflowAnswerFieldPolicy fieldPolicy,
        WorkflowAnswerModelPayload internalPayload,
        WorkflowAnswerModelPayload modelPayload,
        String fieldSemanticsJson,
        WorkflowAnswerChunkPlan chunkPlan,
        String artifactId) {
}