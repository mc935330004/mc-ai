package org.example.ai.agent.graph.runtime.executor;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.config.EndNodeConfig;
import org.example.ai.agent.graph.runtime.*;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EndGraphNodeExecutor  implements GraphNodeExecutor {

    private final GraphRuntimeExpressionResolver expressionResolver;

    @Override
    public GraphNodeType type() {
        return GraphNodeType.END;
    }

    @Override
    public GraphNodeResult execute(
            CompiledGraphNode node,
            GraphExecutionContext context) {

        if (!(node.config()
                instanceof EndNodeConfig config)) {

            return GraphNodeResult.failure(
                    node,
                    "GRAPH_END_CONFIG_INVALID",
                    "END节点配置类型不正确"
            );
        }

        Object result =
                expressionResolver.resolve(
                        config.resultExpression(),
                        context
                );

        return GraphNodeResult.success(
                node,
                result,
                result == null
        );
    }
}