package org.example.ai.agent.workflow.answer.trace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.trace.entity.RunStep;
import org.example.ai.agent.trace.mapper.RunStepMapper;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkCoverage;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerReduceException;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerReductionResult;
import org.springframework.stereotype.Component;

/**
 * 工作流大模型回答链路记录器。
 *
 * 复用现有ai_run_step表：
 * 1. 不新增数据库表；
 * 2. 不修改运行详情接口；
 * 3. 不保存原始业务数据；
 * 4. 遥测写入失败不能影响正常业务回答。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowAnswerTraceRecorder {

    public static final String CHUNK_STEP_TYPE =
            "WORKFLOW_ANSWER_CHUNKS";

    public static final String REDUCTION_STEP_TYPE =
            "WORKFLOW_ANSWER_REDUCE";

    private final RunStepMapper runStepMapper;

    private final ObjectMapper objectMapper;

    public void recordChunkSuccess(
            String runId,
            WorkflowAnswerChunkPlan plan,
            WorkflowAnswerChunkCoverage coverage,
            long durationMs) {

        WorkflowAnswerChunkTraceData data =
                buildChunkData(
                        plan,
                        coverage
                );

        recordSafely(
                runId,
                CHUNK_STEP_TYPE,
                "大模型分块消费",
                "answer/chunks",
                "SUCCESS",
                data,
                null,
                durationMs
        );
    }

    public void recordChunkFailure(
            String runId,
            WorkflowAnswerChunkPlan plan,
            WorkflowAnswerChunkCoverage coverage,
            long durationMs,
            String safeErrorMessage) {

        WorkflowAnswerChunkTraceData data =
                buildChunkData(
                        plan,
                        coverage
                );

        recordSafely(
                runId,
                CHUNK_STEP_TYPE,
                "大模型分块消费",
                "answer/chunks",
                "FAILED",
                data,
                safeErrorMessage,
                durationMs
        );
    }

    public void recordReductionSuccess(
            String runId,
            WorkflowAnswerReductionResult result,
            long durationMs) {

        WorkflowAnswerReductionTraceData data =
                new WorkflowAnswerReductionTraceData(
                        result.reductionLevels(),
                        result.modelCalls(),
                        result.coveredChunks(),
                        true
                );

        recordSafely(
                runId,
                REDUCTION_STEP_TYPE,
                "大模型分层汇总",
                "answer/reduce",
                "SUCCESS",
                data,
                null,
                durationMs
        );
    }

    public void recordReductionFailure(
            String runId,
            WorkflowAnswerChunkCoverage coverage,
            WorkflowAnswerReduceException exception,
            long durationMs,
            String safeErrorMessage) {

        int plannedChunks =
                coverage == null
                        ? 0
                        : coverage.plannedChunks();

        int failedCallSequence =
                exception == null
                        ? plannedChunks
                        : exception.getCallSequence();

        /*
         * 汇总调用从plannedChunks + 1开始，
         * 因此可用调用序号推算已经尝试的汇总调用次数。
         */
        int modelCalls =
                Math.max(
                        0,
                        failedCallSequence
                                - plannedChunks
                );

        int coveredChunks =
                exception == null
                        ? 0
                        : exception
                                .getCoveredIndexes()
                                .size();

        int reductionLevel =
                exception == null
                        ? 0
                        : exception.getReductionLevel();

        WorkflowAnswerReductionTraceData data =
                new WorkflowAnswerReductionTraceData(
                        reductionLevel,
                        modelCalls,
                        coveredChunks,
                        false
                );

        recordSafely(
                runId,
                REDUCTION_STEP_TYPE,
                "大模型分层汇总",
                "answer/reduce",
                "FAILED",
                data,
                safeErrorMessage,
                durationMs
        );
    }

    private WorkflowAnswerChunkTraceData buildChunkData(
            WorkflowAnswerChunkPlan plan,
            WorkflowAnswerChunkCoverage coverage) {

        int plannedChunks =
                plan == null
                        ? 0
                        : plan.totalChunks();

        int succeededChunks =
                coverage == null
                        ? 0
                        : coverage.succeededChunks();

        int failedChunks =
                coverage == null
                        ? 0
                        : coverage.failedChunks();

        int pendingChunks =
                coverage == null
                        ? plannedChunks
                        : coverage.pendingChunks();

        /*
         * 成功分块和失败分块都已经实际调用过模型。
         * pending分块尚未调用，不能计入模型调用次数。
         */
        int modelCalls =
                succeededChunks
                        + failedChunks;

        return new WorkflowAnswerChunkTraceData(
                plannedChunks,
                succeededChunks,
                failedChunks,
                pendingChunks,
                modelCalls,
                plan == null
                        ? 0
                        : plan.sourceCharCount(),
                plan == null
                        ? 0
                        : plan.chunkCharCount(),
                coverage != null
                        && coverage.complete()
        );
    }

    /**
     * 遥测记录必须失败开放。
     *
     * 即使数据库暂时无法写入运行步骤，
     * 也不能让已经成功生成的业务回答失败。
     */
    private void recordSafely(
            String runId,
            String stepType,
            String stepName,
            String executionPath,
            String status,
            Object output,
            String errorMessage,
            long durationMs) {

        if (runId == null
                || runId.isBlank()) {
            return;
        }

        try {
            RunStep step =
                    new RunStep();

            step.setRunId(runId);

            step.setStepNo(
                    nextStepNo(runId)
            );

            step.setStepType(stepType);
            step.setStepName(stepName);
            step.setExecutionPath(
                    executionPath
            );

            /*
             * P2遥测不需要输入JSON。
             * 避免开发人员以后误把分块原文放入inputJson。
             */
            step.setInputJson(null);

            step.setOutputJson(
                    writeJson(output)
            );

            step.setStatus(status);

            step.setErrorMessage(
                    truncateSafeError(
                            errorMessage
                    )
            );

            step.setDurationMs(
                    Math.max(
                            0L,
                            durationMs
                    )
            );

            runStepMapper.insert(step);
        } catch (Exception exception) {
            log.warn(
                    "工作流回答遥测写入失败，runId={}，stepType={}，errorType={}",
                    runId,
                    stepType,
                    exception.getClass()
                            .getSimpleName()
            );
        }
    }

    /**
     * 读取该运行当前最大步骤号，并在其后追加P2步骤。
     */
    private int nextStepNo(String runId) {
        RunStep latestStep =
                runStepMapper.selectOne(
                        Wrappers
                                .<RunStep>lambdaQuery()
                                .eq(
                                        RunStep::getRunId,
                                        runId
                                )
                                .select(
                                        RunStep::getStepNo
                                )
                                .orderByDesc(
                                        RunStep::getStepNo
                                )
                                .last("LIMIT 1")
                );

        if (latestStep == null
                || latestStep.getStepNo() == null) {
            return 1;
        }

        return latestStep.getStepNo() + 1;
    }

    private String writeJson(Object value)
            throws JsonProcessingException {

        return objectMapper.writeValueAsString(
                value
        );
    }

    /**
     * 前端只需要安全业务错误，不保存第三方模型异常详情。
     */
    private String truncateSafeError(
            String errorMessage) {

        if (errorMessage == null
                || errorMessage.isBlank()) {
            return null;
        }

        String normalized =
                errorMessage.trim();

        return normalized.length() <= 500
                ? normalized
                : normalized.substring(
                        0,
                        500
                );
    }
}