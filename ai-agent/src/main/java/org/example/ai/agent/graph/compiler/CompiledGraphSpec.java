package org.example.ai.agent.graph.compiler;

import org.example.ai.agent.graph.model.GraphEdgeSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编译后的不可变GraphSpec。
 *
 * 运行器只能执行本对象，不能直接执行GraphSpec草稿。
 */
public record CompiledGraphSpec(
        String version,
        String code,
        String name,
        String startNodeId,
        String endNodeId,
        Map<String, CompiledGraphNode> nodesById,
        List<GraphEdgeSpec> edges,
        Map<String, List<GraphEdgeSpec>> outgoingEdges,
        Map<String, List<GraphEdgeSpec>> incomingEdges,
        List<String> topologicalOrder) {

    public CompiledGraphSpec {
        nodesById = Collections.unmodifiableMap(
                new LinkedHashMap<>(nodesById)
        );

        edges = Collections.unmodifiableList(
                new ArrayList<>(edges)
        );

        outgoingEdges =
                immutableAdjacency(outgoingEdges);

        incomingEdges =
                immutableAdjacency(incomingEdges);

        topologicalOrder =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                topologicalOrder
                        )
                );
    }

    private static Map<String, List<GraphEdgeSpec>>
    immutableAdjacency(
            Map<String, List<GraphEdgeSpec>> source) {

        Map<String, List<GraphEdgeSpec>> result =
                new LinkedHashMap<>();

        source.forEach((key, value) ->
                result.put(
                        key,
                        Collections.unmodifiableList(
                                new ArrayList<>(value)
                        )
                )
        );

        return Collections.unmodifiableMap(result);
    }
}