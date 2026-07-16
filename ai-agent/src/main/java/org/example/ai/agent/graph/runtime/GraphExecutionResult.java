package org.example.ai.agent.graph.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 整个GraphSpec执行结果。
 */
public record GraphExecutionResult(
        boolean success,
        String runId,
        String graphCode,
        Object result,
        String errorCode,
        String errorMessage,
        Map<String, GraphNodeResult> nodeResults,
        Map<String, Map<String, Object>> variables,
        long durationMs) {

    public GraphExecutionResult {
        nodeResults = Collections.unmodifiableMap(
                new LinkedHashMap<>(nodeResults)
        );

        variables = Collections.unmodifiableMap(
                new LinkedHashMap<>(variables)
        );
    }
}