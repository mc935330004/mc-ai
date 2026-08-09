package org.example.ai.agent.workflow.answer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactService;
import org.example.ai.agent.workflow.answer.chunk.*;
import org.example.ai.agent.workflow.answer.trace.WorkflowAnswerTraceRecorder;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
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

    private final WorkflowAnswerFieldContextResolver fieldContextResolver;

    private final WorkflowAnswerPayloadFactory answerPayloadFactory;
    private final WorkflowAnswerChunkPlanner chunkPlanner;
    private final WorkflowAnswerChunkConsumer chunkConsumer;
    private final WorkflowAnswerSummaryReducer summaryReducer;
    private final WorkflowAnswerTraceRecorder traceRecorder;
    private final ResultArtifactService resultArtifactService;
    /**
     *  准备基础报告。
     *
     * 该方法只执行安全字段过滤、分块和 Artifact 保存，
     * 不调用大模型。
     */
    public WorkflowAnswerPreparation prepareReport(AgentRequest request,WorkflowExecutionOutcome outcome) {
        if (outcome == null) {
            throw new IllegalArgumentException("工作流执行结果不能为空");
        }
        if (!outcome.success()) {
            throw new IllegalStateException("工作流执行失败");
        }
        traceRecorder.recordWorkflowResult(
                outcome.runId(),
                outcome,
                "REPORT"
        );
        WorkflowAnswerFieldPolicy fieldPolicy =fieldContextResolver.resolvePolicy(outcome);
        WorkflowAnswerModelPayload modelPayload =answerPayloadFactory.create(
                        outcome,
                        fieldPolicy.hiddenFieldNames() );
        String fieldSemanticsJson = writeJson(fieldPolicy.visibleFields());
        WorkflowAnswerChunkPlan chunkPlan = chunkPlanner.plan(modelPayload);
        String artifactId = resultArtifactService.save(
                        request,
                        outcome,
                        fieldPolicy,
                        chunkPlan
                );
        return new WorkflowAnswerPreparation(
                outcome,
                fieldPolicy,
                modelPayload,
                fieldSemanticsJson,
                chunkPlan,
                artifactId
        );
    }
    /**
     *  基于已保存 Artifact 生成结构化 AI 分析。
     */
    public WorkflowAnswerAnalysisResult analyzeReport(
            AgentRequest request,
            WorkflowAnswerPreparation preparation) {
        WorkflowAnswerChunkPlan chunkPlan = preparation.chunkPlan();
        long chunkStartedAt = System.currentTimeMillis();
        WorkflowAnswerChunkCoverage coverage =chunkConsumer.consume(
                        request,
                        preparation.outcome().runId(),
                        preparation.fieldSemanticsJson(),
                        chunkPlan
                );

        traceRecorder.recordChunkSuccess(
                preparation.outcome().runId(),
                chunkPlan,
                coverage,
                System.currentTimeMillis() - chunkStartedAt
        );

        long reductionStartedAt = System.currentTimeMillis();

        WorkflowAnswerReductionResult reduction =summaryReducer.reduceStructured(
                        request,
                        preparation.outcome().runId(),
                        preparation.fieldSemanticsJson(),
                        coverage
                );

        if (!reduction.complete(chunkPlan.totalChunks())) {
            throw new IllegalStateException(
                    "结构化分析没有覆盖全部数据分块"
            );
        }

        traceRecorder.recordReductionSuccess(
                preparation.outcome().runId(),
                reduction,
                System.currentTimeMillis() - reductionStartedAt
        );

        ReportSchemaVO.Analysis analysis = parseStructuredAnalysis(reduction.finalAnswer());
        return new WorkflowAnswerAnalysisResult(
                analysis,
                preparation.artifactId()
        );
    }
    /**
     * 解析并校验模型返回的结构化分析 JSON。
     */
    private ReportSchemaVO.Analysis parseStructuredAnalysis(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "AI分析结果不是合法的结构化JSON",
                    exception
            );
        }
        if (root == null || !root.isObject()) {
            throw new IllegalStateException("AI分析结果必须是JSON对象");
        }
        ReportSchemaVO.Analysis analysis = new ReportSchemaVO.Analysis(
                "DONE",
                root.path("summary").asText("").trim(),
                readStringList(root.path("highlights")),
                readStringList(root.path("warnings"))
        );
        // 空JSON不能标记为分析完成，统一进入现有FAILED降级链路。
        if (analysis.summary().isBlank()
                && analysis.highlights().isEmpty()
                && analysis.warnings().isEmpty()) {
            throw new IllegalStateException("AI分析结果没有可展示内容");
        }
        return analysis;
    }

    /**
     *  读取模型返回的字符串数组。
     */
    private List<String> readStringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        }

        return List.copyOf(values);
    }
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
    public WorkflowAnswerComposeResult compose(
            AgentRequest request,
            WorkflowExecutionOutcome outcome) {

        if (outcome == null) {
            return WorkflowAnswerComposeResult.text("工作流没有返回查询结果。");
        }
        if (!outcome.success()) {
            return WorkflowAnswerComposeResult.text("工作流执行失败："+ safeText(outcome.errorMessage()) );
        }
        /*
         *  
         * 先记录工作流实际返回的业务对象和明细数量，
         * 后续即使字段策略、模型分块或最终汇总失败，
         * 仍然可以判断数据是否在进入回答链路前已经缺失。
         *
         * 当前工作流业务查询预期使用REPORT展示。
         * P6-2阶段会将该类型正式写入前后端消息协议。
         */
        traceRecorder.recordWorkflowResult(outcome.runId(),outcome,"REPORT");
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

            return WorkflowAnswerComposeResult.text(
                    "查询已经完成，但字段展示策略加载失败。"
                            + "为保护业务数据，本次未生成详细回答，"
                            + "请管理员检查字段字典发布状态。"
            );
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
        String artifactId = null;

        try {
            /*
             * 将安全业务数据拆分为完整且合法的 JSON 分块。
             */
            chunkPlan =chunkPlanner.plan(modelPayload );

            /*
             * 结果快照必须在调用回答模型前保存。
             * 这样即使后续模型分块消费或汇总失败，
             * 已经查询到的安全业务数据仍然可以保留，
             * 后续能够重新生成报告而不必再次请求PM系统。
             */
            artifactId = resultArtifactService.save(request, outcome, fieldPolicy, chunkPlan);
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
            traceRecorder.recordChunkFailure(
                    outcome.runId(),
                    chunkPlan,
                    exception.getCoverage(),
                    System.currentTimeMillis()
                            - chunkStartedAt,
                    safeText(exception.getMessage())
            );

            /*
             * Artifact已经在模型调用前保存成功。
             * 模型连续失败不能导致业务查询结果一起丢失。
             */
            if (artifactId != null) {
                return WorkflowAnswerComposeResult.recoverable(
                        buildRecoverableAnswer(outcome),
                        artifactId
                );
            }

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
            return WorkflowAnswerComposeResult.report(
                    reductionResult.finalAnswer(),
                    artifactId);
        } catch (
                WorkflowAnswerReduceException exception) {

            traceRecorder.recordReductionFailure(
                    outcome.runId(),
                    coverage,
                    exception,
                    System.currentTimeMillis()
                            - reductionStartedAt,
                    safeText(exception.getMessage())
            );

            /*
             * 分块数据和Artifact都已经完整保存，
             * 这里只是最终AI报告生成失败。
             */
            return WorkflowAnswerComposeResult.recoverable(
                    buildRecoverableAnswer(outcome),
                    artifactId
            );
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
    /**
     * AI报告连续生成失败时的确定性保底回答。
     *
     * 不能把模型异常详情暴露给用户，
     * 也不能让用户误以为需要重新查询PM系统。
     */
    private String buildRecoverableAnswer(
            WorkflowExecutionOutcome outcome) {

        String queryStatus =outcome.partialSuccess()
                        ? "业务查询已完成，但部分业务记录查询失败；"
                          + "成功返回的数据已经完整保存。"
                        : "业务查询已成功完成，查询结果已经完整保存。";

        return """
            ## 查询结果已保存

            %s

            本次 AI 报告生成暂时失败，但不需要重新查询业务系统。

            你可以继续输入：

            - 根据刚才的数据重新生成报告
            - 汇总刚才的数据
            - 统计刚才的某个金额字段
            - 分析刚才查询结果中的异常情况
            """.formatted(queryStatus);
    }
    private String safeText(String value) {
        return value == null
                || value.isBlank()
                ? "未知错误"
                : value;
    }
}