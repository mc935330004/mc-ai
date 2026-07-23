package org.example.ai.agent.graph.runtime.executor;

import org.example.ai.agent.common.enums.ForEachItemStatus;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.GraphSpecLimits;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.graph.config.ForEachMissingValueSkip;
import org.example.ai.agent.graph.runtime.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.Semaphore;

/**
 * FOREACH节点执行器。
 *
 * 每个项目执行同一个编译后的body子图，
 * 但使用完全独立的GraphExecutionContext。
 */
@Component
public class ForEachGraphNodeExecutor
        implements GraphNodeExecutor {

    private final GraphRuntimeExpressionResolver
            expressionResolver;

    private final GraphSubgraphRunner
            subgraphRunner;

    /**
     * 第一层项目循环线程池。
     */
    private final Executor outerItemExecutor;

    /**
     * 第二层业务记录循环线程池。
     */
    private final Executor nestedItemExecutor;

    public ForEachGraphNodeExecutor(
            GraphRuntimeExpressionResolver expressionResolver,
            @Lazy
            GraphSubgraphRunner subgraphRunner,
            @Qualifier("graphForEachExecutor")
            Executor outerItemExecutor,
            @Qualifier("graphNestedForEachExecutor")
            Executor nestedItemExecutor) {

        this.expressionResolver =
                expressionResolver;

        this.subgraphRunner =
                subgraphRunner;

        this.outerItemExecutor = outerItemExecutor;

        this.nestedItemExecutor =nestedItemExecutor;
    }

    @Override
    public GraphNodeType type() {
        return GraphNodeType.FOREACH;
    }

    @Override
    public GraphNodeResult execute(
            CompiledGraphNode node,
            GraphExecutionContext context) {

        if (!(node.config()
                instanceof CompiledForEachNodeConfig config)) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_FOREACH_CONFIG_INVALID",
                    "FOREACH节点配置类型不正确"
            );
        }
        int currentDepth =
                context.getForEachDepth();

        /*
         * 编译器负责正常工作流校验，
         * 运行时保护旧快照、异常快照和绕过编译器的调用。
         */
        if (currentDepth>= GraphSpecLimits.MAX_FOREACH_NESTING_DEPTH) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_NESTING_DEPTH_EXCEEDED",
                    "FOREACH运行深度超过"
                            + GraphSpecLimits
                            .MAX_FOREACH_NESTING_DEPTH
                            + "层"
            );
        }
        Object source;

        try {
            source = expressionResolver.resolve(
                    config.itemsExpression(),
                    context
            );
        } catch (GraphExecutionException exception) {
            return GraphNodeResult.failure(
                    node,
                    exception.getErrorCode(),
                    exception.getMessage()
            );
        }

        List<Object> items = convertToList(source);

        if (items == null) {
            return GraphNodeResult.failure(
                    node,
                    "GRAPH_FOREACH_ITEMS_NOT_COLLECTION",
                    "FOREACH输入必须是数组或集合"
            );
        }

        /*
         * projectKeys 等用户输入集合继续限制为最多 5 条。
         *
         * 当 processAllItems=true 时，表示数据来自可信的上游业务能力，
         * 必须处理全部记录，不能因为记录超过 5 条而截断或失败。
         *
         * 无论是否处理全部记录，并发数仍然最多为 5。
         */
        if (!config.processAllItems() && (items.size() > config.maxItems() || items.size() > 5)) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_FOREACH_ITEM_LIMIT_EXCEEDED",
                    "单次最多处理"
                            + Math.min(config.maxItems(), 5)
                            + "个用户输入项目"
            );
        }

        if (items.isEmpty()) {
            ForEachBatchResult emptyBatch =
                    ForEachBatchResult.from(
                            List.of()
                    );

            return GraphNodeResult.success(
                    node,
                    emptyBatch,
                    true,
                    null,
                    null,
                    "FOREACH执行成功，输入集合为空",
                    Map.of(
                            "totalCount",
                            0
                    )
            );
        }

        /*
         * 在启动任何项目任务前获取父变量快照。
         *
         * 后续每个项目都使用同一份只读初始数据，
         * 不会看到其他项目运行过程中写入的变量。
         */
        Map<String, Object> parentVariables =
                context.snapshotVariables();

        /*
         * 第一层和第二层使用不同线程池。
         *
         * currentDepth=0：
         * 当前是最多5个项目的外层循环。
         *
         * currentDepth=1：
         * 当前是业务接口返回记录的内层循环。
         */
        Executor selectedItemExecutor =
                currentDepth == 0
                        ? outerItemExecutor
                        : nestedItemExecutor;

        List<ForEachItemResult> itemResults = config.continueOnItemError()
                        ? executeConcurrent(
                        node,
                        config,
                        items,
                        context,
                        parentVariables,
                        selectedItemExecutor)
                        : executeSequential(
                        node,
                        config,
                        items,
                        context,
                        parentVariables
                );
        ForEachBatchResult batch =ForEachBatchResult.from(itemResults);

        /*
         * continueOnItemError=false：
         * 任意项目失败，FOREACH整体失败。
         */
        if (!config.continueOnItemError()
                && batch.failureCount() > 0) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_FOREACH_ABORTED_ON_ITEM_ERROR",
                    "FOREACH因单项失败而停止执行",
                    null,
                    null,
                    Map.of(
                            "batchResult",
                            batch
                    )
            );
        }

        /*
         * 所有项目都失败时，不能把整个节点标记为成功。
         */
        if (batch.successCount() == 0
                && batch.failureCount() > 0) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_FOREACH_ALL_ITEMS_FAILED",
                    "全部项目查询失败",
                    null,
                    null,
                    Map.of(
                            "batchResult",
                            batch
                    )
            );
        }

        String summary;
        if (batch.allSucceeded()) {
            summary = "FOREACH执行成功，共处理"
                    + batch.totalCount()
                    + "条记录";
        } else {
            summary = "FOREACH执行完成：可用"
                    + batch.successCount()
                    + "条，其中部分成功"
                    + batch.partialCount()
                    + "条，失败"
                    + batch.failureCount()
                    + "条，跳过"
                    + batch.skippedCount()
                    + "条";
        }

        return GraphNodeResult.success(
                node,
                batch,
                false,
                null,
                null,
                summary,
                Map.of(
                        "totalCount",
                        batch.totalCount(),
                        "successCount",
                        batch.successCount(),
                        "partialCount",
                        batch.partialCount(),
                        "failureCount",
                        batch.failureCount(),
                        "skippedCount",
                        batch.skippedCount()
                )
        );
    }

    /**
     * continueOnItemError=true时并发执行全部项目。
     */
    private List<ForEachItemResult> executeConcurrent(
            CompiledGraphNode node,
            CompiledForEachNodeConfig config,
            List<Object> items,
            GraphExecutionContext parentContext,
            Map<String, Object> parentVariables,
            Executor selectedItemExecutor) {

        /*
         * processAllItems只解除记录总数限制，
         * 不能解除并发限制。
         */
        int concurrencyLimit =config.processAllItems()
                        ? 5: Math.min(config.maxItems(),5);

        int concurrency =Math.max(1,Math.min(config.concurrency(),
                                concurrencyLimit));

        Semaphore semaphore =new Semaphore(concurrency);

        List<CompletableFuture<ForEachItemResult>>
                futures =
                new ArrayList<>();

        for (int index = 0;
             index < items.size();
             index++) {

            int itemIndex = index;
            Object item = items.get(index);

            CompletableFuture<ForEachItemResult> future =
                    CompletableFuture.supplyAsync( () -> executeWithPermit(
                                    semaphore,
                                    node,
                                    config,
                                    itemIndex,
                                    item,
                                    parentContext,
                                    parentVariables
                            ),
                            selectedItemExecutor
                    );
            futures.add(future);
        }

        /*
         * 按future创建顺序join，
         * 保证最终输出顺序与用户输入顺序一致。
         */
        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private ForEachItemResult executeWithPermit(
            Semaphore semaphore,
            CompiledGraphNode node,
            CompiledForEachNodeConfig config,
            int index,
            Object item,
            GraphExecutionContext parentContext,
            Map<String, Object> parentVariables) {

        boolean acquired = false;

        try {
            semaphore.acquire();
            acquired = true;

            return executeSingleItem(
                    node,
                    config,
                    index,
                    item,
                    parentContext,
                    parentVariables
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            return ForEachItemResult.failure(
                    index,
                    item,
                    "GRAPH_FOREACH_INTERRUPTED",
                    "项目查询任务被中断",
                    0
            );

        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    /**
     * continueOnItemError=false时按顺序执行。
     *
     * 避免已经并发提交全部任务后无法真正停止。
     */
    private List<ForEachItemResult> executeSequential(
            CompiledGraphNode node,
            CompiledForEachNodeConfig config,
            List<Object> items,
            GraphExecutionContext parentContext,
            Map<String, Object> parentVariables) {

        List<ForEachItemResult> results =new ArrayList<>();

        boolean aborted = false;

        for (int index = 0; index < items.size();index++) {
            Object item = items.get(index);

            if (aborted) {
                results.add(
                        ForEachItemResult.skipped(
                                index,
                                item
                        )
                );
                continue;
            }

            ForEachItemResult result =
                    executeSingleItem(
                            node,
                            config,
                            index,
                            item,
                            parentContext,
                            parentVariables
                    );

            results.add(result);

            /*
             * SKIPPED 是正常业务跳过，不属于失败。
             * 只有 FAILED 才能中断后续记录。
             */
            if (result.status() == ForEachItemStatus.FAILED) {
                aborted = true;
            }
        }

        return results;
    }

    private ForEachItemResult executeSingleItem(
            CompiledGraphNode node,
            CompiledForEachNodeConfig config,
            int index,
            Object item,
            GraphExecutionContext parentContext,
            Map<String, Object> parentVariables) {

        long startedAt =
                System.currentTimeMillis();

        if (item == null) {
            return ForEachItemResult.failure(
                    index,
                    null,
                    "GRAPH_FOREACH_ITEM_NULL",
                    "项目参数不能为空",
                    0
            );
        }

        try {
            String childPath =
                    parentContext.getExecutionPath()
                            + "/"
                            + node.id()
                            + "["
                            + index
                            + "]";

            GraphExecutionRequest childRequest =
                    GraphExecutionRequest.builder()
                            /*
                             * 保持同一个runId，
                             * 让全部子图步骤归属于同一次Agent运行。
                             */
                            .runId(
                                    parentContext.getRunId()
                            )
                            .userId(
                                    parentContext.getUserId()
                            )
                            .input(
                                    parentContext.getInput()
                            )
                            .userContext(
                                    parentContext.getUserContext()
                            )
                            .authorization(
                                    parentContext.getAuthorization()
                            )
                            .secureContext(
                                    parentContext.getSecureContext()
                            )
                            .currentItem(item)
                            .initialVariables(
                                    parentVariables
                            )
                            .executionPath(childPath)
                            .forEachDepth(parentContext.getForEachDepth() + 1)
                            /*
                             * 当前循环项已经运行在FOREACH隔离线程池中，
                             * 子图内部不再提交到Graph运行线程池。
                             */
                            .inlineExecution(true)
                            .build();


            /*
             * 在执行详情子图前先检查缺值策略。
             *
             * 这一步必须放在 subgraphRunner.execute() 之前，
             * 才能保证无 id 时完全不进入详情能力，更不会发送 HTTP 请求。
             */
            ForEachItemResult skippedResult =evaluateMissingValueSkip(
                            config,
                            childRequest,
                            index,
                            item,
                            startedAt);

            if (skippedResult != null) {
                return skippedResult;
            }

            GraphExecutionResult childResult =subgraphRunner.execute(
                            config.body(),
                            childRequest
                    );
            long duration = System.currentTimeMillis() - startedAt;
            if (childResult == null) {
                return ForEachItemResult.failure(
                        index,
                        item,
                        "GRAPH_FOREACH_CHILD_EMPTY",
                        "项目子图返回空结果",
                        duration
                );
            }

            /*
             * 统一判断子图结果。
             *
             * 如果子图中包含嵌套FOREACH，
             * fromChildResult会把内层失败或跳过传播为PARTIAL_SUCCESS。
             */
            return ForEachItemResult.fromChildResult(
                    index,
                    item,
                    childResult,
                    duration
            );

        } catch (Exception exception) {
            /*
             * 不暴露原始异常消息。
             */
            return ForEachItemResult.failure(
                    index,
                    item,
                    "GRAPH_FOREACH_ITEM_EXECUTION_FAILED",
                    "项目子图执行失败",
                    System.currentTimeMillis()
                            - startedAt
            );
        }
    }

    /**
     * 检查当前循环记录是否需要因缺少必要值而跳过。
     *
     * @return 返回null表示继续执行子图；
     *         返回ForEachItemResult表示当前记录已经被跳过。
     */
    private ForEachItemResult evaluateMissingValueSkip(
            CompiledForEachNodeConfig config,
            GraphExecutionRequest childRequest,
            int index,
            Object item,
            long startedAt) {

        ForEachMissingValueSkip policy =config.missingValueSkip();

        if (policy == null) {
            return null;
        }

        /*
         * 使用当前item创建只读执行上下文，
         * 让$item.id等表达式能够正确解析。
         */
        GraphExecutionContext itemContext =
                GraphExecutionContext.create(
                        childRequest
                );

        Object value =
                expressionResolver.resolve(
                        policy.expression(),
                        itemContext
                );

        if (!isMissingValue(value)) {
            return null;
        }

        return ForEachItemResult.skipped(
                index,
                item,
                policy.code(),
                policy.message(),
                System.currentTimeMillis() - startedAt
        );
    }

    /**
     * id等必要值的统一缺失判断。
     */
    private boolean isMissingValue(Object value) {

        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return !StringUtils.hasText(text.toString());
        }

        return false;
    }


    private List<Object> convertToList(Object source) {

        if (source == null) {
            return null;
        }

        if (source instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }

        if (source.getClass().isArray()) {
            int length =Array.getLength(source);

            List<Object> result =new ArrayList<>(length);

            for (int index = 0;index < length; index++) {
                result.add( Array.get(source, index));
            }
            return result;
        }
        return null;
    }
}