package org.example.ai.agent.workflow.answer.chunk;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * 工作流回答数据块模型消费器。
 *
 * 采用受控并发调用：
 * 1. 并发数由 ai.workflow.answer.analysis.concurrency 控制，默认4；
 * 2. 不会瞬间打爆模型服务，多分块场景显著缩短总耗时；
 * 3. 结果按分块序号排序后返回，覆盖率校验逻辑不变；
 * 4. 任意分块失败仍然整体失败，失败语义与原串行实现一致。
 */
@Slf4j
@Service
public class WorkflowAnswerChunkConsumer {

    private static final String SYSTEM_PROMPT = """
            你是企业PM项目管理系统的数据分块分析助手。

            系统正在把一次完整的工作流查询结果拆成多个数据块，
            当前只要求你分析其中一个数据块。

            必须遵守以下规则：
            1. 只能依据当前数据块中的内容回答。
            2. 不得补充、猜测或编造当前数据块中不存在的数据。
            3. 必须保留项目名称、金额、日期、状态等重要业务事实。
            4. 不输出内部节点ID、能力编码、异常堆栈或鉴权信息。
            5. 不需要生成跨分块最终结论。
            6. 不要省略当前数据块中的业务记录。
            7. 使用清晰、紧凑的中文描述当前分块。
            8. 缺失、空白或未提供的数据不得解释为0。
            9. 不得自行汇总父科目、子科目和合计行，避免重复计算。
            10. 不得自行生成项目结余、成本率、利润率等精确业务指标。
            11. 重要性判断必须依据明确金额、比例、状态或字段业务含义。
            12. 数据不足时只能提示需要核实，不能补充推测值。
            13. 必须区分已知事实、风险判断和建议。
            
            用户可见输出规则：
            1. 只展示用户询问的业务结果、统计数据、必要业务结论和有效文件。
            2. 不展示工作流编码、工作流版本、节点ID、能力编码、运行耗时、
               批处理节点、数组索引、字段路径、内部状态码、异常堆栈和鉴权信息。
            3. 成功、失败和跳过状态只用于计算必要统计，
               不输出“成功记录（索引0）”“跳过记录（索引1）”等执行过程分组。
            4. 不得向用户输出SKIPPED_NO_ID等内部枚举，也不解释内部节点跳过原因。
            5. 不输出原始JSON、接口参数、接口地址和程序调试信息。
            6. 同一业务记录存在文件名称和文件地址时，只输出：
               [文件名称](文件地址)
               禁止在链接文字之外重复展示文件地址。
            7. 不输出模型名称、Token消耗、生成时间、数据来源声明和AI免责声明。
            8. 查询结果不完整时，只说明缺少的业务结果以及用户需要补充的业务条件。
            """;

    private final TrackedChatClientService chatClientService;

    private final ExecutorService chunkExecutor;

    public WorkflowAnswerChunkConsumer(
            TrackedChatClientService chatClientService,
            @Qualifier("workflowAnswerChunkExecutor") ExecutorService chunkExecutor) {
        this.chatClientService = chatClientService;
        this.chunkExecutor = chunkExecutor;
    }

    /**
     * 让模型并发消费全部安全数据块。
     *
     * @param request            当前用户请求
     * @param runId              工作流运行ID
     * @param fieldSemanticsJson 字段中文语义JSON
     * @param plan               完整分块计划
     */
    public WorkflowAnswerChunkCoverage consume(
            AgentRequest request,
            String runId,
            String fieldSemanticsJson,
            WorkflowAnswerChunkPlan plan) {

        validateRequest(request, plan);

        List<WorkflowAnswerChunkAnalysis> analyses = new ArrayList<>(plan.totalChunks());

        /*
         * 受控并发提交：
         * 并发数由专用线程池限制，最多同时调用 concurrency 个分块。
         */
        List<CompletableFuture<WorkflowAnswerChunkAnalysis>> futures =
                new ArrayList<>(plan.totalChunks());

        for (WorkflowAnswerChunk chunk : plan.chunks()) {
            final WorkflowAnswerChunk current = chunk;
            futures.add(CompletableFuture.supplyAsync(() -> {
                String summary = consumeChunkWithRetry(
                        request,
                        runId,
                        fieldSemanticsJson,
                        plan,
                        current
                );
                return new WorkflowAnswerChunkAnalysis(
                        current.index(),
                        current.sourcePointer(),
                        current.sha256(),
                        current.charCount(),
                        summary
                );
            }, chunkExecutor));
        }

        /*
         * 等待全部分块结束。
         * 任意分块失败时，保留其他成功结果用于失败覆盖率台账。
         */
        Throwable firstFailure = null;
        int failedIndex = -1;
        for (int index = 0; index < futures.size(); index++) {
            try {
                analyses.add(futures.get(index).join());
            } catch (CompletionException exception) {
                if (firstFailure == null) {
                    firstFailure = exception.getCause() == null
                            ? exception
                            : exception.getCause();
                    failedIndex = plan.chunks().get(index).index();
                }
            }
        }

        if (firstFailure != null) {
            WorkflowAnswerChunkCoverage failedCoverage =
                    WorkflowAnswerChunkCoverage.failed(
                            plan,
                            analyses,
                            failedIndex
                    );

            /*
             * 日志只记录分块序号和异常类型，
             * 不打印原始业务数据和完整提示词。
             */
            log.error(
                    "工作流回答分块处理失败，runId={}，chunkIndex={}，errorType={}",
                    runId,
                    failedIndex,
                    firstFailure.getClass().getSimpleName()
            );

            throw new WorkflowAnswerChunkConsumeException(
                    "WORKFLOW_ANSWER_CHUNK_CONSUME_FAILED",
                    "第"
                            + failedIndex
                            + "个模型分块处理失败，"
                            + "本次未生成最终业务回答",
                    failedCoverage,
                    firstFailure
            );
        }

        /*
         * 并发完成顺序不确定，按分块序号排序，
         * 保证 coverage.analyses() 与计划顺序一致。
         */
        analyses.sort(
                Comparator.comparingInt(
                        WorkflowAnswerChunkAnalysis::chunkIndex
                )
        );

        WorkflowAnswerChunkCoverage coverage =
                WorkflowAnswerChunkCoverage.completed(plan, analyses);

        /*
         * 理论上循环结束后应该全部成功。
         * 再做一次防御性校验，防止后续代码修改造成漏登记。
         */
        if (!coverage.complete()) {
            throw new WorkflowAnswerChunkConsumeException(
                    "WORKFLOW_ANSWER_CHUNK_COVERAGE_INCOMPLETE",
                    "模型分块覆盖率不完整，本次未生成最终业务回答",
                    coverage,
                    null
            );
        }

        return coverage;
    }

    /**
     * 单个分块最多调用两次。
     *
     * 第一次失败后立即重试一次：
     * 1. 不重新请求PM；
     * 2. 不改变原始分块；
     * 3. 不跳过失败分块；
     * 4. 第二次仍失败才交给外层终止本次报告生成。
     */
    private String consumeChunkWithRetry(AgentRequest request, String runId, String fieldSemanticsJson, WorkflowAnswerChunkPlan plan, WorkflowAnswerChunk chunk) {
        String userPrompt = buildUserPrompt(request, fieldSemanticsJson, plan, chunk);
        Exception lastException = null;

        for (int attempt = 1;attempt <= 2;attempt++) {
            ModelCallType callType = attempt == 1 ? ModelCallType.ANSWER : ModelCallType.ANSWER_RETRY;
            ModelCallContext context = buildContext(request, runId, chunk.index(), callType);

            try {
                ChatResponse response = chatClientService.call(context, SYSTEM_PROMPT, userPrompt);
                /*
                 * 空响应也视为失败并进入重试，
                 * 不能把空字符串登记成成功分块。
                 */
                return extractResponseText(response);
            } catch (Exception exception) {
                lastException = exception;
                if (attempt == 1) {
                    log.warn("工作流回答分块首次调用失败，准备重试，"+ "runId={}，chunkIndex={}，errorType={}", runId, chunk.index(), exception.getClass().getSimpleName());
                }
            }
        }

        throw new IllegalStateException(
                "模型分块连续两次处理失败",
                lastException
        );
    }
    private ModelCallContext buildContext(
            AgentRequest request,
            String runId,
            int callSequence,
            ModelCallType callType) {

        return ModelCallContext.builder()
                .runId(runId)
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                /*
                 * 首次调用使用ANSWER，
                 * 重试使用ANSWER_RETRY。
                 */
                .callType(callType)
                .callSequence(callSequence)
                .modelCode(request.getModelCode())
                .build();
    }

    private String buildUserPrompt(
            AgentRequest request,
            String fieldSemanticsJson,
            WorkflowAnswerChunkPlan plan,
            WorkflowAnswerChunk chunk) {

        return """
                用户问题：
                %s

                字段中文语义：
                %s

                当前分块：
                - 分块序号：%d
                - 分块总数：%d
                - 原始数据位置：%s
                - 原始数据摘要：%s
                - 原始字符数量：%d

                当前安全业务数据：
                %s

                请完整分析当前数据块中的业务事实，
                不要生成跨分块最终结论。
                """.formatted(
                safeText(
                        request.getEffectiveQuestion(),
                        "用户未提供具体问题"
                ),
                safeText(
                        fieldSemanticsJson,
                        "[]"
                ),
                chunk.index(),
                plan.totalChunks(),
                safeText(
                        chunk.sourcePointer(),
                        "/"
                ),
                safeText(
                        chunk.sha256(),
                        "无摘要"
                ),
                chunk.charCount(),
                chunk.payloadJson()
        );
    }


    private String extractResponseText(
            ChatResponse response) {

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {

            throw new IllegalStateException(
                    "模型没有返回有效的分块分析结果"
            );
        }

        String content =
                response.getResult()
                        .getOutput()
                        .getText();

        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException(
                    "模型返回的分块分析结果为空"
            );
        }

        return content.trim();
    }

    /**
     * 在调用模型前检查分块计划完整性。
     */
    private void validateRequest(
            AgentRequest request,
            WorkflowAnswerChunkPlan plan) {

        if (request == null) {
            throw new IllegalArgumentException(
                    "用户请求不能为空"
            );
        }

        if (plan == null) {
            throw new IllegalArgumentException(
                    "工作流分块计划不能为空"
            );
        }

        if (plan.totalChunks() <= 0
                || plan.chunks().isEmpty()) {

            throw new IllegalArgumentException(
                    "工作流分块计划不能为空"
            );
        }

        if (plan.totalChunks()
                != plan.chunks().size()) {

            throw new IllegalArgumentException(
                    "分块总数与实际分块集合数量不一致"
            );
        }

        for (int index = 0; index < plan.chunks().size();index++) {

            WorkflowAnswerChunk chunk =plan.chunks().get(index);

            int expectedIndex = index + 1;

            if (chunk.index() != expectedIndex) {

                throw new IllegalArgumentException(
                        "工作流分块序号不连续，期望："
                                + expectedIndex
                                + "，实际："
                                + chunk.index()
                );
            }

            if (!StringUtils.hasText(chunk.payloadJson())) {

                throw new IllegalArgumentException(
                        "第"+ chunk.index()+ "个工作流分块内容为空");
            }

            if (!StringUtils.hasText(
                    chunk.sha256())) {

                throw new IllegalArgumentException(
                        "第"+ chunk.index() + "个工作流分块缺少数据摘要"
                );
            }
        }
    }

    private String safeText(
            String value,
            String defaultValue) {

        return StringUtils.hasText(value)
                ? value.trim()
                : defaultValue;
    }
}