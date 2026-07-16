package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.ai.agent.graph.model.GraphSpec;

/**
 * FOREACH草稿配置。
 *
 * 编译完成后会转换成CompiledForEachNodeConfig。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ForEachNodeConfig(
        String itemsExpression,
        Integer maxItems,
        Integer concurrency,
        Boolean continueOnItemError,
        GraphSpec body) {
}