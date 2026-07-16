package org.example.ai.agent.graph.config;

import org.example.ai.agent.graph.compiler.CompiledGraphSpec;

/**
 * 编译后的FOREACH配置。
 */
public record CompiledForEachNodeConfig(
        String itemsExpression,
        int maxItems,
        int concurrency,
        boolean continueOnItemError,
        CompiledGraphSpec body)
        implements GraphNodeConfig {
}