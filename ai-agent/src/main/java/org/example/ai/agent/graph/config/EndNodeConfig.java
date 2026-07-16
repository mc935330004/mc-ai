package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * END节点配置。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EndNodeConfig(
        String resultExpression)
        implements GraphNodeConfig {
}