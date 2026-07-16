package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.runtime.GraphExecutionContext;
import org.example.ai.agent.graph.runtime.GraphNodeResult;

/**
 * GraphSpec节点执行器。
 */
public interface GraphNodeExecutor {

    GraphNodeType type();

    GraphNodeResult execute( CompiledGraphNode node,GraphExecutionContext context);
}