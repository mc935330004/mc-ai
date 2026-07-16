package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.runtime.GraphExecutionContext;
import org.example.ai.agent.graph.runtime.GraphNodeResult;
import org.springframework.stereotype.Component;

@Component
public class StartGraphNodeExecutor
        implements GraphNodeExecutor {

    @Override
    public GraphNodeType type() {
        return GraphNodeType.START;
    }

    @Override
    public GraphNodeResult execute(
            CompiledGraphNode node,
            GraphExecutionContext context) {

        return GraphNodeResult.success(
                node,
                context.getInput(),
                context.getInput().isEmpty()
        );
    }
}