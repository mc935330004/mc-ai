package org.example.ai.agent.workflow.answer;

import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;

/**
 * 报告基础数据准备结果。
 * 基础准备阶段不调用大模型，只负责安全投影、分块和 Artifact 保存。
 */
public record WorkflowAnswerPreparation(
        WorkflowExecutionOutcome outcome,
        WorkflowAnswerFieldPolicy fieldPolicy,
        WorkflowAnswerModelPayload modelPayload,
        String fieldSemanticsJson,
        WorkflowAnswerChunkPlan chunkPlan,
        String artifactId) {
}