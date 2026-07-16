package org.example.ai.agent.graph.runtime;

import org.example.ai.agent.common.enums.GraphNodeStatus;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphSpec节点统一执行结果。
 *
 * 不允许保存Authorization、Cookie、Token或原始业务响应。
 */
public record GraphNodeResult(
        String nodeId,
        GraphNodeType nodeType,
        GraphNodeStatus status,
        Object data,
        boolean emptyData,
        String businessCode,
        String businessMessage,
        String errorCode,
        String errorMessage,
        String summary,
        Map<String, Object> metadata,
        long durationMs) {

    public GraphNodeResult {
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(metadata)
                );
    }

    public boolean isSuccess() {
        return status == GraphNodeStatus.SUCCESS;
    }

    public static GraphNodeResult success(
            CompiledGraphNode node,
            Object data,
            boolean emptyData) {

        return success(
                node,
                data,
                emptyData,
                null,
                null,
                node.name() + "执行成功",
                Map.of()
        );
    }

    public static GraphNodeResult success(
            CompiledGraphNode node,
            Object data,
            boolean emptyData,
            String businessCode,
            String businessMessage,
            String summary,
            Map<String, Object> metadata) {

        return new GraphNodeResult(
                node.id(),
                node.type(),
                GraphNodeStatus.SUCCESS,
                data,
                emptyData,
                businessCode,
                businessMessage,
                null,
                null,
                summary,
                metadata,
                0
        );
    }

    public static GraphNodeResult failure(
            CompiledGraphNode node,
            String errorCode,
            String errorMessage) {

        return failure(
                node,
                errorCode,
                errorMessage,
                null,
                null,
                Map.of()
        );
    }

    public static GraphNodeResult failure(
            CompiledGraphNode node,
            String errorCode,
            String errorMessage,
            String businessCode,
            String businessMessage,
            Map<String, Object> metadata) {

        return new GraphNodeResult(
                node.id(),
                node.type(),
                GraphNodeStatus.FAILED,
                null,
                true,
                businessCode,
                businessMessage,
                errorCode,
                errorMessage,
                errorMessage,
                metadata,
                0
        );
    }

    public static GraphNodeResult skipped(
            CompiledGraphNode node,
            String message) {

        return new GraphNodeResult(
                node.id(),
                node.type(),
                GraphNodeStatus.SKIPPED,
                null,
                true,
                null,
                null,
                "GRAPH_NODE_UPSTREAM_FAILED",
                message,
                message,
                Map.of(),
                0
        );
    }

    public GraphNodeResult withDuration(
            long durationMs) {

        return new GraphNodeResult(
                nodeId,
                nodeType,
                status,
                data,
                emptyData,
                businessCode,
                businessMessage,
                errorCode,
                errorMessage,
                summary,
                metadata,
                durationMs
        );
    }

    /**
     * 写入$vars的标准信封。
     */
    public Map<String, Object> toVariableEnvelope() {
        Map<String, Object> envelope =
                new LinkedHashMap<>();

        envelope.put("success", isSuccess());
        envelope.put("status", status.name());
        envelope.put("data", data);
        envelope.put("emptyData", emptyData);
        envelope.put("businessCode", businessCode);
        envelope.put("businessMessage", businessMessage);
        envelope.put("errorCode", errorCode);
        envelope.put("errorMessage", errorMessage);
        envelope.put("summary", summary);

        return Collections.unmodifiableMap(
                envelope
        );
    }
}