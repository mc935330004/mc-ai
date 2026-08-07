package org.example.ai.agent.workflow.answer.artifact;

import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifact;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;

/**
 * 已通过用户、会话、过期时间和完整性校验的结果快照。
 */
public record ResultArtifactSnapshot(
        ResultArtifact artifact,
        WorkflowAnswerChunkPlan chunkPlan,
        String fieldSemanticsJson) {
}