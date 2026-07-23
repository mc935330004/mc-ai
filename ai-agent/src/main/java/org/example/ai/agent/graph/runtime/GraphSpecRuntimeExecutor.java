package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.GraphNodeStatus;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.model.GraphEdgeSpec;
import org.example.ai.agent.graph.runtime.executor.GraphNodeExecutorRegistry;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.StepType;
import org.example.ai.agent.trace.service.RunStepRecorder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * GraphSpec DAG运行器。
 */
@Service
public class GraphSpecRuntimeExecutor implements GraphSubgraphRunner{

    private final GraphNodeExecutorRegistry registry;
    private final Executor taskExecutor;
    private final RunStepRecorder runStepRecorder;
    /**
     * 当前线程同步执行器。
     *
     * 只用于FOREACH隔离任务内部的子图，
     * 普通根工作流仍然使用graphRuntimeExecutor。
     */
    private static final Executor INLINE_EXECUTOR = Runnable::run;

    public GraphSpecRuntimeExecutor(
            GraphNodeExecutorRegistry registry,
            @Qualifier("graphRuntimeExecutor")
            Executor taskExecutor,
            RunStepRecorder runStepRecorder) {

        this.registry = registry;
        this.taskExecutor = taskExecutor;
        this.runStepRecorder = runStepRecorder;
    }
    @Override
    public GraphExecutionResult execute(
            CompiledGraphSpec graph,
            GraphExecutionRequest request) {

        long graphStartedAt =
                System.currentTimeMillis();

        if (graph == null) {
            return failure(
                    request,
                    null,
                    "GRAPH_COMPILED_SPEC_REQUIRED",
                    "编译后的GraphSpec不能为空",
                    Map.of(),
                    Map.of(),
                    graphStartedAt
            );
        }

        GraphExecutionContext context;

        try {
            context =GraphExecutionContext.create(request);

        } catch (GraphExecutionException exception) {
            return failure(
                    request,
                    graph,
                    exception.getErrorCode(),
                    exception.getMessage(),
                    Map.of(),
                    Map.of(),
                    graphStartedAt
            );
        }
        /*
         * 根工作流继续使用Graph运行线程池。
         *
         * FOREACH项目子图已经位于专用隔离线程中，
         * 因此子图节点直接同步执行，避免线程池循环等待。
         */
        Executor selectedNodeExecutor =request.isInlineExecution()? INLINE_EXECUTOR : taskExecutor;

        Map<String, CompletableFuture<GraphNodeResult>>futureByNode = new LinkedHashMap<>();

        Map<String, Integer> stepNumber =
                new LinkedHashMap<>();

        for (int index = 0;index < graph.topologicalOrder().size(); index++) {
            stepNumber.put( graph.topologicalOrder().get(index),index + 1);
        }

        for (String nodeId : graph.topologicalOrder()) {

            CompiledGraphNode node = graph.nodesById().get(nodeId);

            List<CompletableFuture<GraphNodeResult>>
                    upstreamFutures =
                    graph.incomingEdges()
                            .getOrDefault(
                                    nodeId,
                                    List.of()
                            )
                            .stream()
                            .map(GraphEdgeSpec::getSource)
                            .map(futureByNode::get)
                            .toList();

            CompletableFuture<Void> dependencies =
                    CompletableFuture.allOf(
                            upstreamFutures.toArray(
                                    CompletableFuture[]::new
                            )
                    );
            CompletableFuture<GraphNodeResult> nodeFuture = dependencies.thenApplyAsync(
                            ignored -> executeNode(
                                    node,
                                    upstreamFutures,
                                    context,
                                    stepNumber.get(nodeId)
                            ),
                            selectedNodeExecutor
                    );

            futureByNode.put(
                    nodeId,
                    nodeFuture
            );
        }

        CompletableFuture.allOf(
                futureByNode.values()
                        .toArray(
                                CompletableFuture[]::new
                        )
        ).join();

        Map<String, GraphNodeResult> orderedResults = new LinkedHashMap<>();

        for (String nodeId : graph.topologicalOrder()) {

            orderedResults.put(
                    nodeId,
                    futureByNode
                            .get(nodeId)
                            .join()
            );
        }

        GraphNodeResult firstFailure =
                graph.topologicalOrder()
                        .stream()
                        .map(orderedResults::get)
                        .filter(result ->
                                result.status()== GraphNodeStatus.FAILED
                        )
                        .findFirst()
                        .orElse(null);

        GraphNodeResult endResult =
                orderedResults.get(
                        graph.endNodeId()
                );

        long duration =
                System.currentTimeMillis()
                        - graphStartedAt;

        if (firstFailure != null) {
            return new GraphExecutionResult(
                    false,
                    request == null
                            ? null
                            : request.getRunId(),
                    graph.code(),
                    null,
                    firstFailure.errorCode(),
                    firstFailure.errorMessage(),
                    orderedResults,
                    castVariables(
                            context.snapshotVariables()
                    ),
                    duration
            );
        }

        if (endResult == null
                || !endResult.isSuccess()) {

            return new GraphExecutionResult(
                    false,
                    request == null
                            ? null
                            : request.getRunId(),
                    graph.code(),
                    null,
                    "GRAPH_END_NOT_COMPLETED",
                    "END节点没有成功完成",
                    orderedResults,
                    castVariables(
                            context.snapshotVariables()
                    ),
                    duration
            );
        }

        return new GraphExecutionResult(
                true,
                request == null
                        ? null
                        : request.getRunId(),
                graph.code(),
                endResult.data(),
                null,
                null,
                orderedResults,
                castVariables(
                        context.snapshotVariables()
                ),
                duration
        );
    }

    private GraphNodeResult executeNode(
            CompiledGraphNode node,
            List<CompletableFuture<GraphNodeResult>>
                    upstreamFutures,
            GraphExecutionContext context,
            int stepNo) {

        long startedAt =
                System.currentTimeMillis();

        GraphNodeResult result;

        try {
            List<String> failedUpstreamNodes =
                    upstreamFutures.stream()
                            .map(
                                    CompletableFuture::join
                            )
                            .filter(upstream ->
                                    !upstream.isSuccess()
                            )
                            .map(
                                    GraphNodeResult::nodeId
                            )
                            .toList();

            if (!failedUpstreamNodes.isEmpty()) {
                result = GraphNodeResult.skipped(
                        node,
                        "上游节点未成功：" +
                                failedUpstreamNodes
                );

            } else {
                GraphNodeExecutor executor =
                        registry.require(
                                node.type()
                        );

                result = executor.execute(
                        node,
                        context
                );

                if (result == null) {
                    result =
                            GraphNodeResult.failure(
                                    node,
                                    "GRAPH_NODE_EMPTY_RESULT",
                                    "节点执行器返回空结果"
                            );
                }
            }

        } catch (GraphExecutionException exception) {
            result = GraphNodeResult.failure(
                    node,
                    exception.getErrorCode(),
                    exception.getMessage()
            );

        } catch (Exception exception) {
            /*
             * 不返回exception.getMessage()，
             * 防止业务URL、请求参数或响应正文泄漏。
             */
            result = GraphNodeResult.failure(
                    node,
                    "GRAPH_NODE_EXECUTION_FAILED",
                    "节点执行失败"
            );
        }

        result = result.withDuration(
                System.currentTimeMillis()
                        - startedAt
        );

        if (result.isSuccess()
                && node.outputKey() != null) {

            context.publish(
                    node.outputKey(),
                    result
            );
        }

        recordStepSafely(
                context,
                node,
                result,
                stepNo
        );

        return result;
    }

    private void recordStepSafely(
            GraphExecutionContext context,
            CompiledGraphNode node,
            GraphNodeResult result,
            int stepNo) {

        try {
            String capabilityCode = null;

            if (node.config()
                    instanceof CapabilityNodeConfig config) {

                capabilityCode =
                        config.capabilityCode();
            }

            PlanStep traceStep =
                    PlanStep.builder()
                            .stepNo(stepNo)
                            .stepType(node.type()== GraphNodeType.CAPABILITY
                                            ? StepType.BUSINESS_TOOL
                                            : StepType.GRAPH_NODE)
                            .stepName(node.name())
                            .nodeId(node.id())
                            .executionPath(
                                    context.getExecutionPath()
                            )
                            .capabilityCode(
                                    capabilityCode
                            )
                            .outputKey(
                                    node.outputKey()
                            )
                            .build();

            Object traceInput =
                    result.metadata()
                            .getOrDefault(
                                    "auditInput",
                                    Map.of(
                                            "nodeId",
                                            node.id()
                                    )
                            );

            if (result.isSuccess()) {
                runStepRecorder.recordSuccess(
                        context.getRunId(),
                        traceStep,
                        traceInput,
                        result,
                        result.durationMs()
                );
            } else {
                runStepRecorder.recordFailed(
                        context.getRunId(),
                        traceStep,
                        traceInput,
                        result.errorMessage(),
                        result.durationMs()
                );
            }

        } catch (Exception ignored) {
            /*
             * Trace写入失败不能影响主业务查询。
             */
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>>
    castVariables(Map<String, Object> source) {

        Map<String, Map<String, Object>> result =
                new LinkedHashMap<>();

        source.forEach((key, value) -> {
            if (value instanceof Map<?, ?> map) {
                result.put(
                        key,
                        (Map<String, Object>) map
                );
            }
        });

        return result;
    }

    private GraphExecutionResult failure(
            GraphExecutionRequest request,
            CompiledGraphSpec graph,
            String errorCode,
            String errorMessage,
            Map<String, GraphNodeResult> nodeResults,
            Map<String, Map<String, Object>> variables,
            long startedAt) {

        return new GraphExecutionResult(
                false,
                request == null
                        ? null
                        : request.getRunId(),
                graph == null
                        ? null
                        : graph.code(),
                null,
                errorCode,
                errorMessage,
                nodeResults,
                variables,
                System.currentTimeMillis()
                        - startedAt
        );
    }
}