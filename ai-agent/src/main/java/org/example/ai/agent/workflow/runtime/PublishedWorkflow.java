package org.example.ai.agent.workflow.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;

/**
 * 已发布、已校验、可执行的工作流。
 */
public record PublishedWorkflow(
        WorkflowDefinition definition,
        WorkflowVersion version,
        CompiledGraphSpec compiledGraph, JsonNode inputSchema) {
    public PublishedWorkflow {
        inputSchema = inputSchema == null
                ? null
                : inputSchema.deepCopy();
    }
}