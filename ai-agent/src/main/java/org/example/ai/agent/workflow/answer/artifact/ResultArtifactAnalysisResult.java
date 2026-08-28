package org.example.ai.agent.workflow.answer.artifact;

import org.example.ai.agent.answer.model.UnifiedFactSet;

/**
 * 上一轮结果分析的返回值。
 *
 * answer：无法计算时的确定性说明。
 * factSet：统计结果或快照中的业务事实。
 * narrativeOnly：只生成分析文字，不重复展示上一轮业务区块。
 */
public record ResultArtifactAnalysisResult(
        String answer,
        String reportTitle,
        boolean dataComplete,
        UnifiedFactSet factSet,
        boolean narrativeOnly) {
}