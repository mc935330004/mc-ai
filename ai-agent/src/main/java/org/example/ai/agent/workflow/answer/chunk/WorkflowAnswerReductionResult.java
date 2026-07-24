package org.example.ai.agent.workflow.answer.chunk;

import java.util.List;

/**
 * 工作流回答分层汇总结果。
 *
 * @param finalAnswer     最终中文业务回答
 * @param reductionLevels 汇总层级数量
 * @param modelCalls      汇总阶段模型调用次数
 * @param coveredChunks   最终覆盖的原始分块数量
 * @param coveredIndexes  最终覆盖的原始分块序号
 */
public record WorkflowAnswerReductionResult(
        String finalAnswer,
        int reductionLevels,
        int modelCalls,
        int coveredChunks,
        List<Integer> coveredIndexes) {

    public WorkflowAnswerReductionResult {
        coveredIndexes = coveredIndexes == null ? List.of() : List.copyOf(coveredIndexes);
    }

    /**
     * 判断最终回答是否覆盖全部原始分块。
     */
    public boolean complete(int plannedChunks) {
        return plannedChunks > 0  && coveredChunks == plannedChunks
                && coveredIndexes.size() == plannedChunks;
    }
}