package org.example.ai.agent.graph.runtime.executor;

import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.graph.runtime.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

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
     * 项目级并发使用独立线程池。
     *
     * 子图内部节点仍使用graphRuntimeExecutor，
     * 两个线程池不能共用，否则存在嵌套等待死锁风险。
     */
    private final Executor itemExecutor;

    public ForEachGraphNodeExecutor(
            GraphRuntimeExpressionResolver
                    expressionResolver,
            @Lazy
            GraphSubgraphRunner subgraphRunner,
            @Qualifier("graphForEachExecutor")
            Executor itemExecutor) {

        this.expressionResolver =
                expressionResolver;

        this.subgraphRunner =
                subgraphRunner;

        this.itemExecutor =
                itemExecutor;
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

        List<Object> items =
                convertToList(source);

        if (items == null) {
            return GraphNodeResult.failure(
                    node,
                    "GRAPH_FOREACH_ITEMS_NOT_COLLECTION",
                    "FOREACH输入必须是数组或集合"
            );
        }

        /*
         * 运行时必须再次限制数量。
         *
         * 编译器只能验证maxItems配置，
         * 无法预先知道用户实际传入多少个项目。
         */
        if (items.size() > config.maxItems()
                || items.size() > 5) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_FOREACH_ITEM_LIMIT_EXCEEDED",
                    "单次最多查询" +
                            Math.min(
                                    config.maxItems(),
                                    5
                            ) +
                            "个项目"
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

        List<ForEachItemResult> itemResults =
                config.continueOnItemError()
                        ? executeConcurrent(
                                node,
                                config,
                                items,
                                context,
                                parentVariables
                        )
                        : executeSequential(
                                node,
                                config,
                                items,
                                context,
                                parentVariables
                        );

        ForEachBatchResult batch =
                ForEachBatchResult.from(
                        itemResults
                );

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

        String summary =
                batch.failureCount() == 0
                        ? "FOREACH执行成功，共处理" +
                        batch.totalCount() +
                        "个项目"
                        : "FOREACH部分成功：成功" +
                        batch.successCount() +
                        "个，失败" +
                        batch.failureCount() +
                        "个";

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
                        "failureCount",
                        batch.failureCount()
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
            Map<String, Object> parentVariables) {

        int concurrency =
                Math.max(
                        1,
                        Math.min(
                                config.concurrency(),
                                Math.min(
                                        config.maxItems(),
                                        5
                                )
                        )
                );

        Semaphore semaphore =
                new Semaphore(concurrency);

        List<CompletableFuture<ForEachItemResult>>
                futures =
                new ArrayList<>();

        for (int index = 0;
             index < items.size();
             index++) {

            int itemIndex = index;
            Object item = items.get(index);

            CompletableFuture<ForEachItemResult> future =
                    CompletableFuture.supplyAsync(
                            () -> executeWithPermit(
                                    semaphore,
                                    node,
                                    config,
                                    itemIndex,
                                    item,
                                    parentContext,
                                    parentVariables
                            ),
                            itemExecutor
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

        List<ForEachItemResult> results =
                new ArrayList<>();

        boolean aborted = false;

        for (int index = 0;
             index < items.size();
             index++) {

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

            if (!result.isSuccess()) {
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
                            .build();

            GraphExecutionResult childResult =
                    subgraphRunner.execute(
                            config.body(),
                            childRequest
                    );

            long duration =
                    System.currentTimeMillis()
                            - startedAt;

            if (childResult == null) {
                return ForEachItemResult.failure(
                        index,
                        item,
                        "GRAPH_FOREACH_CHILD_EMPTY",
                        "项目子图返回空结果",
                        duration
                );
            }

            if (!childResult.success()) {
                return ForEachItemResult.failure(
                        index,
                        item,
                        childResult.errorCode(),
                        childResult.errorMessage(),
                        duration
                );
            }

            return ForEachItemResult.success(
                    index,
                    item,
                    childResult.result(),
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

    private List<Object> convertToList(
            Object source) {

        if (source == null) {
            return null;
        }

        if (source instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }

        if (source.getClass().isArray()) {
            int length =
                    Array.getLength(source);

            List<Object> result =
                    new ArrayList<>(length);

            for (int index = 0;
                 index < length;
                 index++) {

                result.add(
                        Array.get(source, index)
                );
            }

            return result;
        }

        return null;
    }
}