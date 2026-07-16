package org.example.ai.agent.graph.runtime.executor;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.common.enums.MergeMode;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.config.MergeNodeConfig;
import org.example.ai.agent.graph.runtime.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MergeGraphNodeExecutor implements GraphNodeExecutor {

    private final GraphRuntimeExpressionResolver expressionResolver;

    @Override
    public GraphNodeType type() {
        return GraphNodeType.MERGE;
    }

    @Override
    public GraphNodeResult execute(
            CompiledGraphNode node,
            GraphExecutionContext context) {

        if (!(node.config()
                instanceof MergeNodeConfig config)) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_MERGE_CONFIG_INVALID",
                    "MERGE节点配置类型不正确"
            );
        }

        Object result;

        if (config.mode() == MergeMode.OBJECT) {
            Map<String, Object> merged =
                    new LinkedHashMap<>();

            config.mappings().forEach(
                    (key, expression) ->
                            merged.put(
                                    key,
                                    expressionResolver.resolve(
                                            expression,
                                            context
                                    )
                            )
            );

            result = merged;

        } else {
            List<Object> merged =
                    new ArrayList<>();

            config.sources().forEach(
                    expression ->
                            merged.add(
                                    expressionResolver.resolve(
                                            expression,
                                            context
                                    )
                            )
            );

            result = merged;
        }

        return GraphNodeResult.success(
                node,
                result,
                isEmpty(result)
        );
    }

    private boolean isEmpty(Object value) {
        if (value == null) {
            return true;
        }

        if (value instanceof Map<?, ?> map) {
            return map.isEmpty();
        }

        if (value instanceof List<?> list) {
            return list.isEmpty();
        }

        return value instanceof String text
                && text.isBlank();
    }
}