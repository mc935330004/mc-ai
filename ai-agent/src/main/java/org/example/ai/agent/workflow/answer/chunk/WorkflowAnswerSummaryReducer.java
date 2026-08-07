package org.example.ai.agent.workflow.answer.chunk;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流回答分层汇总器。
 *
 * 工作方式：
 * 1. 接收全部原始分块摘要；
 * 2. 按字符数和数量分组；
 * 3. 每组生成中间摘要；
 * 4. 中间摘要继续分组；
 * 5. 最终生成面向用户的中文回答。
 */
@Slf4j
@Service
public class WorkflowAnswerSummaryReducer {

    private static final String SYSTEM_PROMPT = """
            你是企业PM项目管理系统的业务回答汇总助手。

            系统已经让模型逐块读取了全部工作流安全数据。
            你现在只能依据这些分块摘要生成合并结果。

            必须遵守：
            1. 不得补充摘要中不存在的项目、金额、日期或状态。
            2. 不得丢弃任何来源分块中的重要业务事实。
            3. 不输出节点ID、能力编码、字段路径、异常堆栈。
            4. 不输出原始JSON。
            5. 不自行改变金额单位。
            6. 最终回答使用清晰、准确的中文。
            7. 必须完整保留分块摘要中的文件链接；
               同时存在文件名称和文件地址时，统一输出：[文件名称](文件地址)，禁止输出裸地址。
           
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

    private final int maxGroupItems;

    private final int maxGroupChars;

    private final int maxReductionLevels;

    public WorkflowAnswerSummaryReducer(
            TrackedChatClientService chatClientService,
            @Value("${ai.workflow.answer.reduce.max-group-items:8}")
            int maxGroupItems,
            @Value("${ai.workflow.answer.reduce.max-group-chars:12000}")
            int maxGroupChars,
            @Value("${ai.workflow.answer.reduce.max-levels:10}")
            int maxReductionLevels) {

        if (maxGroupItems < 2) {
            throw new IllegalArgumentException(
                    "maxGroupItems不能小于2"
            );
        }

        if (maxGroupChars <= 0) {
            throw new IllegalArgumentException(
                    "maxGroupChars必须大于0"
            );
        }

        if (maxReductionLevels <= 0) {
            throw new IllegalArgumentException(
                    "maxReductionLevels必须大于0"
            );
        }

        this.chatClientService =chatClientService;

        this.maxGroupItems = maxGroupItems;

        this.maxGroupChars = maxGroupChars;

        this.maxReductionLevels = maxReductionLevels;
    }

    public WorkflowAnswerReductionResult reduce(
            AgentRequest request,
            String runId,
            String fieldSemanticsJson,
            WorkflowAnswerChunkCoverage coverage) {

        validateCoverage(coverage);

        List<ReductionItem> currentItems =
                coverage.analyses()
                        .stream()
                        .map(analysis ->
                                new ReductionItem(
                                        List.of(
                                                analysis.chunkIndex()
                                        ),
                                        analysis.summary()
                                )
                        )
                        .toList();

        ReductionState state =
                new ReductionState(
                        coverage.plannedChunks() + 1
                );

        for (int level = 1; level <= maxReductionLevels; level++) {

            List<List<ReductionItem>> groups =groupItems(currentItems);

            /*
             * 当前全部摘要能够放进一个提示词时，
             * 直接生成最终回答，不再多调用一次模型。
             */
            if (groups.size() == 1) {
                List<ReductionItem> finalGroup = groups.get(0);

                List<Integer> coveredIndexes = collectIndexes(finalGroup);

                verifyCoveredIndexes(
                        coveredIndexes,
                        coverage.plannedChunks()
                );

                String finalAnswer =callModel(
                                request,
                                runId,
                                fieldSemanticsJson,
                                coverage,
                                finalGroup,
                                level,
                                true,
                                state
                        );

                return new WorkflowAnswerReductionResult(
                        finalAnswer,
                        level,
                        state.modelCalls(),
                        coveredIndexes.size(),
                        coveredIndexes
                );
            }

            List<ReductionItem> nextItems =new ArrayList<>( groups.size());

            for (List<ReductionItem> group : groups) {

                List<Integer> sourceIndexes = collectIndexes(group);

                String mergedSummary =
                        callModel(
                                request,
                                runId,
                                fieldSemanticsJson,
                                coverage,
                                group,
                                level,
                                false,
                                state
                        );

                nextItems.add(new ReductionItem(
                                sourceIndexes,
                                mergedSummary
                        )
                );
            }

            currentItems =List.copyOf(nextItems);
        }

        throw new WorkflowAnswerReduceException(
                "WORKFLOW_ANSWER_REDUCE_LEVEL_EXCEEDED",
                "工作流摘要汇总层级超过安全上限，本次未生成最终回答",
                maxReductionLevels,
                state.nextCallSequence(),
                List.of(),
                null
        );
    }

    /**
     * 按数量和字符数进行贪心分组。
     */
    private List<List<ReductionItem>> groupItems(
            List<ReductionItem> items) {

        List<List<ReductionItem>> groups =
                new ArrayList<>();

        List<ReductionItem> currentGroup =
                new ArrayList<>();

        int currentChars = 0;

        for (ReductionItem item : items) {
            int itemChars =
                    estimateChars(item);

            /*
             * 单个摘要本身超过上限时明确失败，
             * 不能substring截断摘要。
             */
            if (itemChars > maxGroupChars) {
                throw new WorkflowAnswerReduceException(
                        "WORKFLOW_ANSWER_REDUCE_ITEM_TOO_LARGE",
                        "单个分块摘要超过汇总字符上限",
                        0,
                        0,
                        item.sourceIndexes(),
                        null
                );
            }

            boolean itemLimitReached = currentGroup.size() >= maxGroupItems;

            boolean charLimitReached =!currentGroup.isEmpty() && currentChars
                            + itemChars
                            > maxGroupChars;

            if (itemLimitReached || charLimitReached) {

                groups.add( List.copyOf(currentGroup));

                currentGroup.clear();
                currentChars = 0;
            }

            currentGroup.add(item);
            currentChars += itemChars;
        }

        if (!currentGroup.isEmpty()) {
            groups.add(List.copyOf(currentGroup));
        }

        return List.copyOf(groups);
    }

    /**
     * 执行分层汇总模型调用。
     *
     * 每个汇总节点最多尝试两次。
     * 重试只使用同一批摘要，不读取PM业务系统。
     */
    private String callModel(
            AgentRequest request,
            String runId,
            String fieldSemanticsJson,
            WorkflowAnswerChunkCoverage coverage,
            List<ReductionItem> items,
            int level,
            boolean finalCall,
            ReductionState state) {

        int callSequence =state.allocateCallSequence();

        List<Integer> sourceIndexes =collectIndexes(items);

        String userPrompt =
                buildPrompt(
                        request,
                        fieldSemanticsJson,
                        coverage,
                        items,
                        level,
                        finalCall
                );

        Exception lastException = null;

        for (int attempt = 1; attempt <= 2;attempt++) {
            if (attempt == 2) {
                state.recordRetry();
            }

            ModelCallType callType =
                    attempt == 1
                            ? ModelCallType.ANSWER
                            : ModelCallType.ANSWER_RETRY;

            ModelCallContext context =
                    ModelCallContext.builder()
                            .runId(runId)
                            .conversationId(
                                    request.getConversationId()
                            )
                            .userId(
                                    request.getUserId()
                            )
                            .callType(callType)
                            .callSequence(callSequence)
                            .modelCode(
                                    request.getModelCode()
                            )
                            .build();

            try {
                ChatResponse response =
                        chatClientService.call(
                                context,
                                SYSTEM_PROMPT,
                                userPrompt
                        );

                /*
                 * 空文本同样进入重试，
                 * 不能把空报告当成生成成功。
                 */
                return extractText(response);

            } catch (Exception exception) {
                lastException = exception;

                if (attempt == 1) {
                    log.warn(
                            "工作流回答汇总首次调用失败，准备重试，"
                                    + "runId={}，level={}，callSequence={}，errorType={}",
                            runId,
                            level,
                            callSequence,
                            exception.getClass()
                                    .getSimpleName()
                    );
                }
            }
        }

        log.error(
                "工作流回答汇总连续两次失败，"
                        + "runId={}，level={}，callSequence={}，errorType={}",
                runId,
                level,
                callSequence,
                lastException == null
                        ? "UNKNOWN"
                        : lastException
                        .getClass()
                        .getSimpleName()
        );

        throw new WorkflowAnswerReduceException(
                "WORKFLOW_ANSWER_REDUCE_FAILED",
                "工作流回答在第"
                        + level
                        + "层汇总连续两次失败",
                level,
                callSequence,
                sourceIndexes,
                lastException
        );
    }

    private String buildPrompt(
            AgentRequest request,
            String fieldSemanticsJson,
            WorkflowAnswerChunkCoverage coverage,
            List<ReductionItem> items,
            int level,
            boolean finalCall) {

        StringBuilder summaries =
                new StringBuilder();

        for (int index = 0;index < items.size();index++) {
            ReductionItem item = items.get(index);
            summaries.append("摘要")
                    .append(index + 1)
                    .append("，来源原始分块：")
                    .append(item.sourceIndexes())
                    .append(System.lineSeparator())
                    .append(item.summary())
                    .append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        String taskDescription =
                finalCall
                        ? """
                        请根据全部摘要生成最终中文业务回答。
                        回答用户真正关心的结果，并说明成功、失败和跳过情况。
                        """
                        : """
                        请把当前这些摘要合并成一份忠实的中间摘要。
                        必须保留所有重要业务事实，不要生成额外结论。
                        """;

        return """
                用户问题：
                %s

                当前汇总层级：
                %d

                是否最终汇总：
                %s

                原始分块覆盖率：
                - 计划分块：%d
                - 成功消费：%d
                - 失败分块：%d
                - 未执行分块：%d

                字段中文语义：
                %s

                待汇总摘要：
                %s

                当前任务：
                %s
                """.formatted(
                safeText(
                        request.getEffectiveQuestion(),
                        "用户未提供具体问题"
                ),
                level,
                finalCall,
                coverage.plannedChunks(),
                coverage.succeededChunks(),
                coverage.failedChunks(),
                coverage.pendingChunks(),
                safeText(
                        fieldSemanticsJson,
                        "[]"
                ),
                summaries,
                taskDescription
        );
    }

    private String extractText(
            ChatResponse response) {

        if (response == null
                || response.getResult() == null
                || response.getResult()
                        .getOutput() == null) {

            throw new IllegalStateException(
                    "模型没有返回有效的汇总结果"
            );
        }

        String text =
                response.getResult()
                        .getOutput()
                        .getText();

        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException(
                    "模型返回的汇总结果为空"
            );
        }

        return text.trim();
    }

    /**
     * 汇总来源分块序号，并保证顺序稳定。
     */
    private List<Integer> collectIndexes(
            List<ReductionItem> items) {

        Set<Integer> indexes =
                new LinkedHashSet<>();

        for (ReductionItem item : items) {
            indexes.addAll(
                    item.sourceIndexes()
            );
        }

        return indexes.stream()
                .sorted(
                        Comparator.naturalOrder()
                )
                .toList();
    }

    /**
     * 最终回答必须覆盖1～plannedChunks的全部序号。
     */
    private void verifyCoveredIndexes(
            List<Integer> indexes,
            int plannedChunks) {

        if (indexes.size() != plannedChunks) {
            throw new WorkflowAnswerReduceException(
                    "WORKFLOW_ANSWER_REDUCE_COVERAGE_INCOMPLETE",
                    "最终汇总没有覆盖全部原始分块",
                    0,
                    0,
                    indexes,
                    null
            );
        }

        for (int index = 1;
             index <= plannedChunks;
             index++) {

            if (!indexes.contains(index)) {
                throw new WorkflowAnswerReduceException(
                        "WORKFLOW_ANSWER_REDUCE_COVERAGE_INCOMPLETE",
                        "最终汇总缺少原始分块：" + index,
                        0,
                        0,
                        indexes,
                        null
                );
            }
        }
    }

    private void validateCoverage(
            WorkflowAnswerChunkCoverage coverage) {

        if (coverage == null) {
            throw new IllegalArgumentException(
                    "分块覆盖率不能为空"
            );
        }

        if (!coverage.complete()) {
            throw new IllegalArgumentException(
                    "分块覆盖率不是100%，禁止生成最终回答"
            );
        }

        if (coverage.analyses().size()
                != coverage.plannedChunks()) {

            throw new IllegalArgumentException(
                    "分块摘要数量与计划分块数量不一致"
            );
        }

        List<Integer> indexes =
                coverage.analyses()
                        .stream()
                        .map(
                                WorkflowAnswerChunkAnalysis::chunkIndex
                        )
                        .sorted()
                        .toList();

        verifyCoveredIndexes(
                indexes,
                coverage.plannedChunks()
        );
    }

    private int estimateChars(
            ReductionItem item) {

        int summaryChars =
                item.summary() == null
                        ? 0
                        : item.summary().length();

        // 为分块序号、换行和提示词标签预留少量空间。
        return summaryChars + 100;
    }

    private String safeText(
            String value,
            String defaultValue) {

        return StringUtils.hasText(value)
                ? value.trim()
                : defaultValue;
    }

    /**
     * 汇总过程中使用的内部摘要节点。
     */
    private record ReductionItem(
            List<Integer> sourceIndexes,
            String summary) {

        private ReductionItem {
            sourceIndexes = sourceIndexes == null
                    ? List.of()
                    : List.copyOf(sourceIndexes);

            summary = summary == null
                    ? ""
                    : summary;
        }
    }

    /**
     * 维护汇总阶段模型调用序号和调用次数。
     */
    private static final class ReductionState {

        private int nextCallSequence;

        private int modelCalls;

        private ReductionState(
                int firstCallSequence) {

            this.nextCallSequence =
                    firstCallSequence;
        }

        private int allocateCallSequence() {
            int allocated =
                    nextCallSequence;

            nextCallSequence++;
            modelCalls++;

            return allocated;
        }
        /**
         * 记录一次实际模型重试。
         *
         * callSequence保持不变，
         * 通过ModelCallType.ANSWER_RETRY区分重试记录。
         */
        private void recordRetry() {
            modelCalls++;
        }
        private int nextCallSequence() {
            return nextCallSequence;
        }

        private int modelCalls() {
            return modelCalls;
        }
    }
}