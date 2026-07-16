package org.example.ai.agent.graph.runtime.executor;

import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.runtime.GraphExecutionException;
import org.example.ai.agent.graph.runtime.GraphNodeExecutor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 节点执行器注册表。
 */
@Component
public class GraphNodeExecutorRegistry {

    private final Map<GraphNodeType, GraphNodeExecutor>
            executors =
            new EnumMap<>(GraphNodeType.class);

    public GraphNodeExecutorRegistry(
            List<GraphNodeExecutor> executorList) {

        for (GraphNodeExecutor executor : executorList) {
            GraphNodeExecutor previous =
                    executors.put(
                            executor.type(),
                            executor
                    );

            if (previous != null) {
                throw new IllegalStateException(
                        "节点执行器重复注册：" +
                                executor.type()
                );
            }
        }
    }

    public GraphNodeExecutor require(
            GraphNodeType type) {

        GraphNodeExecutor executor =
                executors.get(type);

        if (executor == null) {
            throw new GraphExecutionException(
                    "GRAPH_NODE_EXECUTOR_NOT_FOUND",
                    "找不到节点执行器：" + type
            );
        }

        return executor;
    }
}