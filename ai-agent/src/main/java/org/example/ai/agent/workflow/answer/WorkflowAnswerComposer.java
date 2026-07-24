package org.example.ai.agent.workflow.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.workflow.answer.chunk.*;
import org.example.ai.agent.workflow.answer.trace.WorkflowAnswerTraceRecorder;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.stereotype.Service;

/**
 * 根据工作流结构化结果生成最终中文回答。
 *
 * 当前回答链路：
 * 安全字段投影
 * → 数据分块
 * → 逐块模型消费
 * → 覆盖率校验
 * → 分层汇总
 * → 最终回答
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowAnswerComposer {

    private final ObjectMapper objectMapper;

    private final WorkflowAnswerFieldContextResolver
            fieldContextResolver;

    private final WorkflowAnswerPayloadFactory
            answerPayloadFactory;

    private final WorkflowAnswerChunkPlanner
            chunkPlanner;

    private final WorkflowAnswerChunkConsumer
            chunkConsumer;

    private final WorkflowAnswerSummaryReducer
            summaryReducer;
    private final WorkflowAnswerTraceRecorder traceRecorder;

    /**
     * 根据工作流执行结果生成最终中文回答。
     *
     * 完整处理链路：
     * 1. 校验工作流结果；
     * 2. 根据字段字典过滤隐藏字段；
     * 3. 将安全数据拆分成多个 JSON 数据块；
     * 4. 逐块调用大模型；
     * 5. 校验全部分块是否完整消费；
     * 6. 对分块摘要进行分层汇总；
     * 7. 记录安全的 P2 回答链路遥测。
     */
    public String compose(
            AgentRequest request,
            WorkflowExecutionOutcome outcome) {

        if (outcome == null) {
            return "工作流没有返回查询结果。";
        }

        if (!outcome.success()) {
            return "工作流执行失败："
                    + safeText(outcome.errorMessage());
        }

        /*
         * 第一阶段：加载字段展示策略。
         *
         * 如果无法确定字段是否允许展示，
         * 必须阻止原始业务数据发送给大模型。
         */
        WorkflowAnswerFieldPolicy fieldPolicy;

        try {
            fieldPolicy =
                    fieldContextResolver.resolvePolicy(
                            outcome
                    );
        } catch (Exception exception) {
            log.error(
                    "工作流字段展示策略加载失败，已阻止业务数据发送给模型，"
                            + "runId={}，errorType={}",
                    outcome.runId(),
                    exception.getClass().getSimpleName()
            );

            return "查询已经完成，但字段展示策略加载失败。"
                    + "为保护业务数据，本次未生成详细回答，"
                    + "请管理员检查字段字典发布状态。";
        }

        /*
         * 这里只生成经过字段隐藏过滤后的安全数据。
         *
         * 后续分块器只能接收 modelPayload，
         * 不能接收业务系统的原始响应。
         */
        WorkflowAnswerModelPayload modelPayload =
                answerPayloadFactory.create(
                        outcome,
                        fieldPolicy.hiddenFieldNames()
                );

        String fieldSemanticsJson =
                writeJson(
                        fieldPolicy.visibleFields()
                );

        /*
         * 第二阶段：数据分块并逐块调用大模型。
         */
        long chunkStartedAt =
                System.currentTimeMillis();

        WorkflowAnswerChunkPlan chunkPlan = null;
        WorkflowAnswerChunkCoverage coverage;

        try {
            /*
             * 将安全业务数据拆分为完整且合法的 JSON 分块。
             */
            chunkPlan =chunkPlanner.plan(modelPayload );

            /*
             * 注意：
             * chunkConsumer.consume() 在整个方法中只能调用一次。
             *
             * 每个分块都会独立调用大模型，
             * 任意一个分块失败都会抛出明确异常。
             */
            coverage =chunkConsumer.consume( request,
                            outcome.runId(),
                            fieldSemanticsJson,
                            chunkPlan);

            /*
             * 分块全部消费成功后，记录安全遥测。
             *
             * 遥测只包含：
             * - 分块数量；
             * - 成功、失败、待处理数量；
             * - 字符数量；
             * - 模型调用次数。
             *
             * 不包含原始业务数据和模型摘要。
             */
            traceRecorder.recordChunkSuccess(
                    outcome.runId(),
                    chunkPlan,
                    coverage,
                    System.currentTimeMillis()
                            - chunkStartedAt
            );
        } catch (
                WorkflowAnswerChunkConsumeException exception) {

            /*
             * 分块消费失败时，异常中携带当前覆盖率台账。
             */
            traceRecorder.recordChunkFailure(
                    outcome.runId(),
                    chunkPlan,
                    exception.getCoverage(),
                    System.currentTimeMillis()
                            - chunkStartedAt,
                    safeText(exception.getMessage())
            );

            throw exception;
        } catch (RuntimeException exception) {
            /*
             * 分块计划生成失败等异常可能没有 coverage，
             * 仍然需要生成一条失败遥测。
             */
            traceRecorder.recordChunkFailure(
                    outcome.runId(),
                    chunkPlan,
                    null,
                    System.currentTimeMillis()
                            - chunkStartedAt,
                    "工作流安全数据分块失败"
            );

            throw exception;
        }

        /*
         * 第三阶段：对全部分块摘要进行分层汇总。
         */
        long reductionStartedAt =
                System.currentTimeMillis();

        try {
            WorkflowAnswerReductionResult reductionResult =
                    summaryReducer.reduce(
                            request,
                            outcome.runId(),
                            fieldSemanticsJson,
                            coverage
                    );

            /*
             * 最终回答必须覆盖全部原始分块。
             *
             * 即使汇总器返回了文本，
             * 只要覆盖率不完整，就不能作为最终回答返回。
             */
            if (!reductionResult.complete(
                    chunkPlan.totalChunks())) {

                throw new IllegalStateException(
                        "工作流最终回答未覆盖全部数据分块"
                );
            }

            /*
             * 分层汇总和最终覆盖率校验全部成功后，
             * 再记录成功遥测。
             */
            traceRecorder.recordReductionSuccess(
                    outcome.runId(),
                    reductionResult,
                    System.currentTimeMillis()
                            - reductionStartedAt
            );

            return reductionResult.finalAnswer();
        } catch (
                WorkflowAnswerReduceException exception) {

            /*
             * 汇总器明确抛出的异常中包含：
             * - 失败层级；
             * - 调用序号；
             * - 已覆盖分块编号。
             */
            traceRecorder.recordReductionFailure(
                    outcome.runId(),
                    coverage,
                    exception,
                    System.currentTimeMillis()
                            - reductionStartedAt,
                    safeText(exception.getMessage())
            );

            throw exception;
        } catch (RuntimeException exception) {
            /*
             * 覆盖率防御校验失败等普通运行异常，
             * 同样记录汇总失败，但不暴露第三方模型异常详情。
             */
            traceRecorder.recordReductionFailure(
                    outcome.runId(),
                    coverage,
                    null,
                    System.currentTimeMillis()
                            - reductionStartedAt,
                    "工作流分层汇总失败"
            );

            throw exception;
        }
    }


    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "工作流回答上下文序列化失败",
                    exception
            );
        }
    }

    private String safeText(String value) {
        return value == null
                || value.isBlank()
                ? "未知错误"
                : value;
    }
}