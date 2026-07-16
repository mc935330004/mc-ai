package org.example.ai.agent.graph.compiler;

/**
 * GraphSpec编译错误。
 *
 * graphPath、nodeId、edgeId供以后前端定位并标红。
 */
public record GraphValidationError(
        String code,
        String graphPath,
        String nodeId,
        String edgeId,
        String message) {
}