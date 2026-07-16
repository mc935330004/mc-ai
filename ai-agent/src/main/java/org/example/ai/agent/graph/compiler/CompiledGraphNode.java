package org.example.ai.agent.graph.compiler;

import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.config.GraphNodeConfig;

/**
 * 编译后的不可变节点。
 */
public record CompiledGraphNode(
        String id,
        GraphNodeType type,
        String name,
        String description,
        String outputKey,
        GraphNodeConfig config) {
}