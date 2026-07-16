package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.graph.compiler.CompiledGraphSpec;

/**
 * 子图执行接口。
 *
 * FOREACH依赖本接口，而不是直接依赖具体运行器类。
 */
@FunctionalInterface
public interface GraphSubgraphRunner {

    GraphExecutionResult execute(
            CompiledGraphSpec graph,
            GraphExecutionRequest request);
}