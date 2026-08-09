package org.example.ai.agent.workflow.answer;

import org.example.ai.agent.chat.vo.ReportSchemaVO;

/**
 * 结构化 AI 报告分析结果。
 */
public record WorkflowAnswerAnalysisResult(
        ReportSchemaVO.Analysis analysis,
        String artifactId) {
}