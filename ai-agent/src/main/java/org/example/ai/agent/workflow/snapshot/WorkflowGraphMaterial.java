package org.example.ai.agent.workflow.snapshot;

import org.example.ai.agent.graph.compiler.GraphCompilationResult;

/**
 * GraphSpec规范化、校验后的中间结果。
 */
public record WorkflowGraphMaterial(
        String normalizedGraphSpecJson,
        String checksum,
        GraphCompilationResult compilationResult,
        int nodeCount,
        int edgeCount) {

    public boolean valid() {
        return compilationResult != null
                && compilationResult.valid();
    }
}