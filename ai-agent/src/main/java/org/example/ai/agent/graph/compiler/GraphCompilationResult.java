package org.example.ai.agent.graph.compiler;

import java.util.List;

/**
 * GraphSpec编译结果。
 */
public record GraphCompilationResult(
        boolean valid,
        List<GraphValidationError> errors,
        CompiledGraphSpec compiledGraph) {

    public static GraphCompilationResult success(
            CompiledGraphSpec graph) {

        return new GraphCompilationResult(
                true,
                List.of(),
                graph
        );
    }

    public static GraphCompilationResult failure(
            List<GraphValidationError> errors) {

        return new GraphCompilationResult(
                false,
                List.copyOf(errors),
                null
        );
    }
}