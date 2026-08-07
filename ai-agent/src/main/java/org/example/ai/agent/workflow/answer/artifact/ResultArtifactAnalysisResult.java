package org.example.ai.agent.workflow.answer.artifact;

/**
 * 上一轮结果分析回答。
 */
public record ResultArtifactAnalysisResult(
        String answer,
        String reportTitle,
        boolean dataComplete) {
}