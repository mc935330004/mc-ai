package org.example.ai.agent.workflow.answer.artifact;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.workflow.answer.ResultArtifactStatisticsService;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkConsumeException;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkCoverage;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkConsumer;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerReduceException;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerReductionResult;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerSummaryReducer;
import org.example.ai.agent.workflow.answer.trace.WorkflowAnswerTraceRecorder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 基于上一轮安全结果快照回答追问。
 *
 * 当前阶段复用已有模型分块和汇总链路。
 * P6-4会在此服务前增加确定性本地统计。
 */
@Service
@RequiredArgsConstructor
public class ResultArtifactAnalysisService {

    private final ResultArtifactService artifactService;
    private final WorkflowAnswerChunkConsumer chunkConsumer;
    private final WorkflowAnswerSummaryReducer summaryReducer;
    private final WorkflowAnswerTraceRecorder traceRecorder;
    /**
     * 数学统计优先使用Java本地确定性计算。
     */
    private final ResultArtifactStatisticsService statisticsService;
    public ResultArtifactAnalysisResult analyze( AgentRequest request,String runId) {
        ResultArtifactSnapshot snapshot =artifactService.loadComplete(
                        request.getUserId(),
                        request.getConversationId(),
                        request.getResultArtifactId()
                );

        /*
         * P6-4：
         * 数学统计优先在Java中计算。
         *
         * 如果返回Optional.empty，说明属于总结、解释、异常分析等
         * 定性问题，继续执行原有模型分块和Reducer链路。
         */
        var localStatistics =
                statisticsService.tryAnalyze(
                        request,
                        runId,
                        snapshot
                );

        if (localStatistics.isPresent()) {
            return localStatistics.get();
        }

        WorkflowAnswerChunkPlan chunkPlan = snapshot.chunkPlan();

        String fieldSemanticsJson =StringUtils.hasText(
                        snapshot.fieldSemanticsJson()
                )
                        ? snapshot.fieldSemanticsJson()
                        : "[]";

        WorkflowAnswerChunkCoverage coverage;

        long chunkStartedAt =
                System.currentTimeMillis();

        try {
            coverage = chunkConsumer.consume(
                    request,
                    runId,
                    fieldSemanticsJson,
                    chunkPlan
            );

            traceRecorder.recordChunkSuccess(
                    runId,
                    chunkPlan,
                    coverage,
                    System.currentTimeMillis()
                            - chunkStartedAt
            );
        } catch (
                WorkflowAnswerChunkConsumeException exception) {

            traceRecorder.recordChunkFailure(
                    runId,
                    chunkPlan,
                    exception.getCoverage(),
                    System.currentTimeMillis()
                            - chunkStartedAt,
                    "上一轮结果分块分析失败"
            );

            /*
             * Artifact已经存在并通过完整性校验。
             * 报告模型连续失败不能让前端显示系统异常，
             * 也不能重新请求PM业务系统。
             */
            return buildRecoverableResult(snapshot);
        } catch (RuntimeException exception) {
            traceRecorder.recordChunkFailure(
                    runId,
                    chunkPlan,
                    null,
                    System.currentTimeMillis()
                            - chunkStartedAt,
                    "上一轮结果读取或分析失败"
            );

            throw exception;
        }

        long reductionStartedAt =
                System.currentTimeMillis();

        try {
            WorkflowAnswerReductionResult reduction =
                    summaryReducer.reduce(
                            request,
                            runId,
                            fieldSemanticsJson,
                            coverage
                    );

            if (!reduction.complete(
                    chunkPlan.totalChunks())) {

                throw new IllegalStateException(
                        "上一轮结果分析没有覆盖全部数据分块"
                );
            }

            traceRecorder.recordReductionSuccess(
                    runId,
                    reduction,
                    System.currentTimeMillis()
                            - reductionStartedAt
            );

            String reportTitle =
                    StringUtils.hasText(
                            snapshot.artifact()
                                    .getWorkflowName()
                    )
                            ? snapshot.artifact()
                                    .getWorkflowName()
                            : "业务数据分析报告";

            return new ResultArtifactAnalysisResult(
                    reduction.finalAnswer(),
                    reportTitle,
                    Boolean.TRUE.equals(
                            snapshot.artifact()
                                    .getDataComplete()
                    )
            );

        } catch (
                WorkflowAnswerReduceException exception) {

            traceRecorder.recordReductionFailure(
                    runId,
                    coverage,
                    exception,
                    System.currentTimeMillis()
                            - reductionStartedAt,
                    "上一轮结果汇总失败"
            );

            /*
             * 查询结果快照仍然有效，
             * 只提示报告暂时无法生成。
             */
            return buildRecoverableResult(snapshot);
        } catch (RuntimeException exception) {
            traceRecorder.recordReductionFailure(
                    runId,
                    coverage,
                    null,
                    System.currentTimeMillis()
                            - reductionStartedAt,
                    "上一轮结果汇总失败"
            );

            throw exception;
        }
    }
    /**
     * 基于仍然有效的Artifact返回确定性恢复提示。
     *
     * 不删除Artifact、不清除会话状态、不重新请求PM。
     */
    private ResultArtifactAnalysisResult buildRecoverableResult(
            ResultArtifactSnapshot snapshot) {
        boolean dataComplete =Boolean.TRUE.equals(
                        snapshot.artifact()
                                .getDataComplete()
                );

        String dataStatus =
                dataComplete
                        ? "上一轮查询结果仍然完整保存在当前会话中。"
                        : "上一轮成功返回的数据仍然保存在当前会话中，"
                          + "但原业务查询存在部分失败或跳过记录。";

        String answer = """
            ## 报告暂未生成

            %s

            本次 AI 报告连续生成失败，但不需要重新查询业务系统。

            你可以稍后继续输入：

            - 根据刚才的数据重新生成报告
            - 汇总刚才的数据
            - 统计刚才的某个金额字段

            如果需要获取业务系统最新数据，请明确输入“重新查询”或“刷新数据”。
            """.formatted(dataStatus);

        return new ResultArtifactAnalysisResult(
                answer,
                "报告暂未生成",
                dataComplete
        );
    }
}