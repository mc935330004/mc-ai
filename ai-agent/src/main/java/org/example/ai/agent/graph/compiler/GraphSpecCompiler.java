package org.example.ai.agent.graph.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.common.enums.MergeMode;
import org.example.ai.agent.graph.config.*;
import org.example.ai.agent.graph.model.GraphEdgeSpec;
import org.example.ai.agent.graph.model.GraphNodeSpec;
import org.example.ai.agent.graph.model.GraphSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * GraphSpec编译器。
 *
 * 职责：
 * 1. 校验图协议；
 * 2. 校验节点配置；
 * 3. 校验能力是否可调用；
 * 4. 校验DAG拓扑；
 * 5. 编译ForEach子图；
 * 6. 生成不可变运行模型。
 */
@Component
@RequiredArgsConstructor
public class GraphSpecCompiler {

    public static final String SUPPORTED_VERSION =
            "1.0";

    private static final int MAX_NODES_PER_GRAPH = 50;
    private static final int MAX_EDGES_PER_GRAPH = 100;
    private static final int MAX_TOTAL_NODES = 100;

    /**
     * root深度为0，ForEach body深度为1。
     *
     * 第一版不允许ForEach中继续嵌套ForEach。
     */
    private static final int MAX_FOREACH_DEPTH = 1;

    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile(
                    "^[a-z][a-z0-9_-]{0,63}$"
            );

    /**
     * GraphSpec只能读取公开运行变量。
     *
     * $secure只能由能力请求绑定读取，
     * 不允许工作流直接操作认证和租户安全变量。
     */
    private static final Pattern EXPRESSION_PATTERN =
            Pattern.compile(
                    "^\\$(input|vars|item)" +
                            "(?:\\.[\\p{L}\\p{N}_-]+)*$"
            );

    private final ObjectMapper objectMapper;
    private final GraphCapabilityCatalog capabilityCatalog;

    public GraphCompilationResult compile(
            GraphSpec graphSpec) {

        List<GraphValidationError> errors =
                new ArrayList<>();

        CompilationCounter counter =
                new CompilationCounter();

        CompiledGraphSpec compiled =
                compileGraph(
                        graphSpec,
                        "root",
                        0,
                        true,
                        errors,
                        counter
                );

        if (!errors.isEmpty() || compiled == null) {
            return GraphCompilationResult.failure(
                    errors
            );
        }

        return GraphCompilationResult.success(
                compiled
        );
    }

    private CompiledGraphSpec compileGraph(
            GraphSpec graph,
            String graphPath,
            int depth,
            boolean rootGraph,
            List<GraphValidationError> errors,
            CompilationCounter counter) {

        int initialErrorCount = errors.size();

        if (graph == null) {
            addGraphError(
                    errors,
                    "GRAPH_SPEC_MISSING",
                    graphPath,
                    "GraphSpec不能为空"
            );
            return null;
        }

        validateGraphMetadata(
                graph,
                graphPath,
                rootGraph,
                errors
        );

        List<GraphNodeSpec> nodes =
                graph.getNodes() == null
                        ? List.of()
                        : graph.getNodes();

        List<GraphEdgeSpec> edges =
                graph.getEdges() == null
                        ? List.of()
                        : graph.getEdges();

        if (nodes.size() < 2
                || nodes.size() > MAX_NODES_PER_GRAPH) {

            addGraphError(
                    errors,
                    "GRAPH_NODE_COUNT_INVALID",
                    graphPath,
                    "每个GraphSpec必须包含2到" +
                            MAX_NODES_PER_GRAPH +
                            "个节点"
            );
        }

        if (edges.size() > MAX_EDGES_PER_GRAPH) {
            addGraphError(
                    errors,
                    "GRAPH_EDGE_COUNT_INVALID",
                    graphPath,
                    "每个GraphSpec最多包含" +
                            MAX_EDGES_PER_GRAPH +
                            "条边"
            );
        }

        counter.totalNodes += nodes.size();

        if (counter.totalNodes > MAX_TOTAL_NODES
                && !counter.totalLimitReported) {

            counter.totalLimitReported = true;

            addGraphError(
                    errors,
                    "GRAPH_TOTAL_NODE_LIMIT_EXCEEDED",
                    graphPath,
                    "工作流及全部子图的节点总数不能超过" +
                            MAX_TOTAL_NODES
            );
        }

        Map<String, GraphNodeSpec> nodeMap =
                new LinkedHashMap<>();

        Map<String, GraphNodeConfig> compiledConfigs =
                new LinkedHashMap<>();

        Set<String> outputKeys =
                new LinkedHashSet<>();

        List<String> startNodeIds =
                new ArrayList<>();

        List<String> endNodeIds =
                new ArrayList<>();

        for (GraphNodeSpec node : nodes) {
            if (!validateNodeBase(
                    node,
                    graphPath,
                    nodeMap,
                    outputKeys,
                    errors)) {

                continue;
            }

            if (node.getType() == GraphNodeType.START) {
                startNodeIds.add(node.getId());
            }

            if (node.getType() == GraphNodeType.END) {
                endNodeIds.add(node.getId());
            }

            GraphNodeConfig config = compileNodeConfig(
                            node,
                            graphPath,
                            depth,
                            errors,
                            counter
                    );

            compiledConfigs.put(
                    node.getId(),
                    config
            );
        }

        if (startNodeIds.size() != 1) {
            addGraphError(
                    errors,
                    "GRAPH_START_NODE_INVALID",
                    graphPath,
                    "每个GraphSpec必须且只能包含一个START节点"
            );
        }

        if (endNodeIds.size() != 1) {
            addGraphError(
                    errors,
                    "GRAPH_END_NODE_INVALID",
                    graphPath,
                    "每个GraphSpec必须且只能包含一个END节点"
            );
        }

        Map<String, List<GraphEdgeSpec>> outgoing =
                initializeAdjacency(nodeMap.keySet());

        Map<String, List<GraphEdgeSpec>> incoming =
                initializeAdjacency(nodeMap.keySet());

        List<GraphEdgeSpec> validEdges =
                validateEdges(
                        edges,
                        nodeMap,
                        outgoing,
                        incoming,
                        graphPath,
                        errors
                );

        String startNodeId =
                startNodeIds.size() == 1
                        ? startNodeIds.get(0)
                        : null;

        String endNodeId =
                endNodeIds.size() == 1
                        ? endNodeIds.get(0)
                        : null;

        validateDegrees(
                nodeMap,
                incoming,
                outgoing,
                startNodeId,
                endNodeId,
                graphPath,
                errors
        );

        List<String> topologicalOrder =
                buildTopologicalOrder(
                        nodeMap,
                        incoming,
                        outgoing,
                        graphPath,
                        errors
                );

        validateReachability(
                nodeMap,
                outgoing,
                incoming,
                startNodeId,
                endNodeId,
                graphPath,
                errors
        );

        if (errors.size() > initialErrorCount) {
            return null;
        }

        Map<String, CompiledGraphNode> compiledNodes =
                new LinkedHashMap<>();

        nodeMap.forEach((id, node) ->
                compiledNodes.put(
                        id,
                        new CompiledGraphNode(
                                id,
                                node.getType(),
                                node.getName(),
                                node.getDescription(),
                                node.getOutputKey(),
                                compiledConfigs.get(id)
                        )
                )
        );

        return new CompiledGraphSpec(
                graph.getVersion(),
                graph.getCode(),
                graph.getName(),
                startNodeId,
                endNodeId,
                compiledNodes,
                validEdges,
                outgoing,
                incoming,
                topologicalOrder
        );
    }

    private void validateGraphMetadata(
            GraphSpec graph,
            String graphPath,
            boolean rootGraph,
            List<GraphValidationError> errors) {

        if (!SUPPORTED_VERSION.equals(
                graph.getVersion())) {

            addGraphError(
                    errors,
                    "GRAPH_VERSION_UNSUPPORTED",
                    graphPath,
                    "不支持的GraphSpec版本：" +
                            graph.getVersion()
            );
        }

        /*
         * ForEach body是内部子图，
         * 不强制配置独立code和name。
         */
        if (rootGraph
                && !StringUtils.hasText(
                        graph.getCode())) {

            addGraphError(
                    errors,
                    "GRAPH_CODE_REQUIRED",
                    graphPath,
                    "根工作流code不能为空"
            );
        }

        if (rootGraph
                && !StringUtils.hasText(
                        graph.getName())) {

            addGraphError(
                    errors,
                    "GRAPH_NAME_REQUIRED",
                    graphPath,
                    "根工作流name不能为空"
            );
        }
    }

    private boolean validateNodeBase(
            GraphNodeSpec node,
            String graphPath,
            Map<String, GraphNodeSpec> nodeMap,
            Set<String> outputKeys,
            List<GraphValidationError> errors) {

        if (node == null) {
            addGraphError(
                    errors,
                    "GRAPH_NODE_NULL",
                    graphPath,
                    "节点不能为空"
            );
            return false;
        }

        String nodeId = node.getId();

        if (!StringUtils.hasText(nodeId)
                || !IDENTIFIER_PATTERN
                .matcher(nodeId)
                .matches()) {

            addNodeError(
                    errors,
                    "GRAPH_NODE_ID_INVALID",
                    graphPath,
                    nodeId,
                    "节点ID必须以小写字母开头，" +
                            "只允许小写字母、数字、下划线和中划线"
            );
            return false;
        }

        if (nodeMap.containsKey(nodeId)) {
            addNodeError(
                    errors,
                    "GRAPH_NODE_ID_DUPLICATED",
                    graphPath,
                    nodeId,
                    "节点ID重复：" + nodeId
            );
            return false;
        }

        nodeMap.put(nodeId, node);

        if (node.getType() == null) {
            addNodeError(
                    errors,
                    "GRAPH_NODE_TYPE_REQUIRED",
                    graphPath,
                    nodeId,
                    "节点类型不能为空"
            );
            return true;
        }

        boolean producesOutput =
                node.getType()
                        == GraphNodeType.CAPABILITY
                        || node.getType()
                        == GraphNodeType.FOREACH
                        || node.getType()
                        == GraphNodeType.MERGE;

        if (producesOutput) {
            if (!StringUtils.hasText(
                    node.getOutputKey())) {

                addNodeError(
                        errors,
                        "GRAPH_OUTPUT_KEY_REQUIRED",
                        graphPath,
                        nodeId,
                        node.getType() +
                                "节点必须配置outputKey"
                );

            } else if (!IDENTIFIER_PATTERN
                    .matcher(node.getOutputKey())
                    .matches()) {

                addNodeError(
                        errors,
                        "GRAPH_OUTPUT_KEY_INVALID",
                        graphPath,
                        nodeId,
                        "outputKey格式不合法：" +
                                node.getOutputKey()
                );

            } else if (!outputKeys.add(
                    node.getOutputKey())) {

                addNodeError(
                        errors,
                        "GRAPH_OUTPUT_KEY_DUPLICATED",
                        graphPath,
                        nodeId,
                        "outputKey重复：" +
                                node.getOutputKey()
                );
            }
        } else if (StringUtils.hasText(
                node.getOutputKey())) {

            addNodeError(
                    errors,
                    "GRAPH_OUTPUT_KEY_NOT_ALLOWED",
                    graphPath,
                    nodeId,
                    node.getType() +
                            "节点不能配置outputKey"
            );
        }

        return true;
    }

    private GraphNodeConfig compileNodeConfig(
            GraphNodeSpec node,
            String graphPath,
            int depth,
            List<GraphValidationError> errors,
            CompilationCounter counter) {

        return switch (node.getType()) {
            case START ->
                    compileStartConfig(
                            node,
                            graphPath,
                            errors
                    );

            case CAPABILITY ->
                    compileCapabilityConfig(
                            node,
                            graphPath,
                            errors
                    );

            case FOREACH ->
                    compileForEachConfig(
                            node,
                            graphPath,
                            depth,
                            errors,
                            counter
                    );

            case MERGE ->
                    compileMergeConfig(
                            node,
                            graphPath,
                            errors
                    );

            case END ->
                    compileEndConfig(
                            node,
                            graphPath,
                            errors
                    );
        };
    }

    private GraphNodeConfig compileStartConfig(
            GraphNodeSpec node,
            String graphPath,
            List<GraphValidationError> errors) {

        JsonNode config = node.getConfig();

        if (config != null
                && !config.isNull()
                && (!config.isObject()
                || config.size() > 0)) {

            addNodeError(
                    errors,
                    "GRAPH_START_CONFIG_NOT_ALLOWED",
                    graphPath,
                    node.getId(),
                    "START节点不能配置运行参数"
            );
        }

        return null;
    }

    private CapabilityNodeConfig compileCapabilityConfig(
            GraphNodeSpec node,
            String graphPath,
            List<GraphValidationError> errors) {

        CapabilityNodeConfig config =
                readConfig(
                        node,
                        CapabilityNodeConfig.class,
                        graphPath,
                        errors
                );

        if (config == null) {
            return null;
        }

        if (!StringUtils.hasText(
                config.capabilityCode())) {

            addNodeError(
                    errors,
                    "GRAPH_CAPABILITY_CODE_REQUIRED",
                    graphPath,
                    node.getId(),
                    "CAPABILITY节点必须配置capabilityCode"
            );
            return config;
        }

        try {
            if (!capabilityCatalog.isCallable( config.capabilityCode())) {
                addNodeError(
                        errors,
                        "GRAPH_CAPABILITY_NOT_CALLABLE",
                        graphPath,
                        node.getId(),
                        "能力不存在、未启用或未发布：" +
                                config.capabilityCode()
                );
            }
        } catch (Exception exception) {
            addNodeError(
                    errors,
                    "GRAPH_CAPABILITY_CATALOG_UNAVAILABLE",
                    graphPath,
                    node.getId(),
                    "能力目录暂时不可用"
            );
        }

        if (config.inputMapping().size() > 50) {
            addNodeError(
                    errors,
                    "GRAPH_INPUT_MAPPING_TOO_LARGE",
                    graphPath,
                    node.getId(),
                    "单个节点最多配置50个输入映射"
            );
        }

        config.inputMapping().forEach(
                (key, value) -> {
                    if (!StringUtils.hasText(key)) {
                        addNodeError(
                                errors,
                                "GRAPH_INPUT_NAME_INVALID",
                                graphPath,
                                node.getId(),
                                "输入参数名称不能为空"
                        );
                    }

                    validateConfiguredValue(
                            value,
                            graphPath,
                            node.getId(),
                            errors
                    );
                }
        );

        return config;
    }

    private CompiledForEachNodeConfig compileForEachConfig(
            GraphNodeSpec node,
            String graphPath,
            int depth,
            List<GraphValidationError> errors,
            CompilationCounter counter) {

        ForEachNodeConfig config =
                readConfig(
                        node,
                        ForEachNodeConfig.class,
                        graphPath,
                        errors
                );

        if (config == null) {
            return null;
        }

        validateExpression(
                config.itemsExpression(),
                graphPath,
                node.getId(),
                "itemsExpression",
                errors
        );

        int maxItems =
                config.maxItems() == null
                        ? 5
                        : config.maxItems();

        if (maxItems < 1 || maxItems > 5) {
            addNodeError(
                    errors,
                    "GRAPH_FOREACH_MAX_ITEMS_INVALID",
                    graphPath,
                    node.getId(),
                    "FOREACH的maxItems必须在1到5之间"
            );
        }

        int concurrency =
                config.concurrency() == null
                        ? Math.min(2, maxItems)
                        : config.concurrency();

        if (concurrency < 1
                || concurrency > 5
                || concurrency > maxItems) {

            addNodeError(
                    errors,
                    "GRAPH_FOREACH_CONCURRENCY_INVALID",
                    graphPath,
                    node.getId(),
                    "FOREACH的concurrency必须在1到5之间，" +
                            "且不能大于maxItems"
            );
        }

        boolean continueOnItemError =
                config.continueOnItemError() == null
                        || config.continueOnItemError();

        CompiledGraphSpec body = null;

        if (depth >= MAX_FOREACH_DEPTH) {
            addNodeError(
                    errors,
                    "GRAPH_NESTING_DEPTH_EXCEEDED",
                    graphPath,
                    node.getId(),
                    "第一版不允许FOREACH子图继续嵌套FOREACH"
            );

        } else if (config.body() == null) {
            addNodeError(
                    errors,
                    "GRAPH_FOREACH_BODY_REQUIRED",
                    graphPath,
                    node.getId(),
                    "FOREACH必须配置body子图"
            );

        } else {
            body = compileGraph(
                    config.body(),
                    graphPath + "/" +
                            node.getId() +
                            ".body",
                    depth + 1,
                    false,
                    errors,
                    counter
            );
        }

        return new CompiledForEachNodeConfig(
                config.itemsExpression(),
                maxItems,
                concurrency,
                continueOnItemError,
                body
        );
    }

    private MergeNodeConfig compileMergeConfig(
            GraphNodeSpec node,
            String graphPath,
            List<GraphValidationError> errors) {

        MergeNodeConfig config =
                readConfig(
                        node,
                        MergeNodeConfig.class,
                        graphPath,
                        errors
                );

        if (config == null) {
            return null;
        }

        if (config.mode() == MergeMode.OBJECT) {
            if (config.mappings().isEmpty()) {
                addNodeError(
                        errors,
                        "GRAPH_MERGE_MAPPING_REQUIRED",
                        graphPath,
                        node.getId(),
                        "OBJECT模式必须配置mappings"
                );
            }

            config.mappings().forEach(
                    (key, expression) ->
                            validateExpression(
                                    expression,
                                    graphPath,
                                    node.getId(),
                                    "mappings." + key,
                                    errors
                            )
            );

        } else if (config.mode() == MergeMode.LIST) {
            if (config.sources().isEmpty()) {
                addNodeError(
                        errors,
                        "GRAPH_MERGE_SOURCE_REQUIRED",
                        graphPath,
                        node.getId(),
                        "LIST模式必须配置sources"
                );
            }

            for (int index = 0;
                 index < config.sources().size();
                 index++) {

                validateExpression(
                        config.sources().get(index),
                        graphPath,
                        node.getId(),
                        "sources[" + index + "]",
                        errors
                );
            }
        }

        return config;
    }

    private EndNodeConfig compileEndConfig(
            GraphNodeSpec node,
            String graphPath,
            List<GraphValidationError> errors) {

        EndNodeConfig config =
                readConfig(
                        node,
                        EndNodeConfig.class,
                        graphPath,
                        errors
                );

        if (config == null) {
            return null;
        }

        validateExpression(
                config.resultExpression(),
                graphPath,
                node.getId(),
                "resultExpression",
                errors
        );

        return config;
    }

    private <T> T readConfig(
            GraphNodeSpec node,
            Class<T> configType,
            String graphPath,
            List<GraphValidationError> errors) {

        JsonNode config = node.getConfig();

        if (config == null
                || config.isNull()
                || !config.isObject()) {

            addNodeError(
                    errors,
                    "GRAPH_NODE_CONFIG_REQUIRED",
                    graphPath,
                    node.getId(),
                    node.getType() +
                            "节点必须配置JSON对象config"
            );
            return null;
        }

        try {
            ObjectReader reader = objectMapper
                    .readerFor(configType)
                    .with(
                            DeserializationFeature
                                    .FAIL_ON_UNKNOWN_PROPERTIES
                    );

            return reader.readValue( objectMapper.treeAsTokens(config));

        } catch (JsonProcessingException exception) {
            addNodeError(
                    errors,
                    "GRAPH_NODE_CONFIG_INVALID",
                    graphPath,
                    node.getId(),
                    node.getType() +
                            "节点配置无效：" +
                            safeMessage(exception)
            );
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<GraphEdgeSpec> validateEdges(
            List<GraphEdgeSpec> edges,
            Map<String, GraphNodeSpec> nodes,
            Map<String, List<GraphEdgeSpec>> outgoing,
            Map<String, List<GraphEdgeSpec>> incoming,
            String graphPath,
            List<GraphValidationError> errors) {

        List<GraphEdgeSpec> valid =
                new ArrayList<>();

        Set<String> edgeIds =
                new LinkedHashSet<>();

        Set<String> connections =
                new LinkedHashSet<>();

        for (GraphEdgeSpec edge : edges) {
            if (edge == null) {
                addGraphError(
                        errors,
                        "GRAPH_EDGE_NULL",
                        graphPath,
                        "边不能为空"
                );
                continue;
            }

            if (!StringUtils.hasText(edge.getId())
                    || !IDENTIFIER_PATTERN
                    .matcher(edge.getId())
                    .matches()) {

                addEdgeError(
                        errors,
                        "GRAPH_EDGE_ID_INVALID",
                        graphPath,
                        edge.getId(),
                        "边ID格式不合法"
                );
                continue;
            }

            if (!edgeIds.add(edge.getId())) {
                addEdgeError(
                        errors,
                        "GRAPH_EDGE_ID_DUPLICATED",
                        graphPath,
                        edge.getId(),
                        "边ID重复：" + edge.getId()
                );
                continue;
            }

            if (!nodes.containsKey(edge.getSource())
                    || !nodes.containsKey(edge.getTarget())) {

                addEdgeError(
                        errors,
                        "GRAPH_EDGE_ENDPOINT_NOT_FOUND",
                        graphPath,
                        edge.getId(),
                        "边的source或target节点不存在"
                );
                continue;
            }

            if (edge.getSource()
                    .equals(edge.getTarget())) {

                addEdgeError(
                        errors,
                        "GRAPH_SELF_LOOP_NOT_ALLOWED",
                        graphPath,
                        edge.getId(),
                        "节点不能连接到自身"
                );
                continue;
            }

            String connection =
                    edge.getSource() +
                            "->" +
                            edge.getTarget();

            if (!connections.add(connection)) {
                addEdgeError(
                        errors,
                        "GRAPH_EDGE_DUPLICATED",
                        graphPath,
                        edge.getId(),
                        "重复连接：" + connection
                );
                continue;
            }

            valid.add(edge);

            outgoing.get(edge.getSource())
                    .add(edge);

            incoming.get(edge.getTarget())
                    .add(edge);
        }

        return valid;
    }

    private void validateDegrees(
            Map<String, GraphNodeSpec> nodes,
            Map<String, List<GraphEdgeSpec>> incoming,
            Map<String, List<GraphEdgeSpec>> outgoing,
            String startNodeId,
            String endNodeId,
            String graphPath,
            List<GraphValidationError> errors) {

        nodes.forEach((nodeId, node) -> {
            int inputCount =
                    incoming.get(nodeId).size();

            int outputCount =
                    outgoing.get(nodeId).size();

            if (nodeId.equals(startNodeId)) {
                if (inputCount != 0 || outputCount != 1) {
                    addNodeError(
                            errors,
                            "GRAPH_START_DEGREE_INVALID",
                            graphPath,
                            nodeId,
                            "START节点不能有入边且必须只有一条出边"
                    );
                }
                return;
            }

            if (nodeId.equals(endNodeId)) {
                if (inputCount < 1 || outputCount != 0) {
                    addNodeError(
                            errors,
                            "GRAPH_END_DEGREE_INVALID",
                            graphPath,
                            nodeId,
                            "END节点必须有入边且不能有出边"
                    );
                }
                return;
            }

            if (inputCount < 1 || outputCount < 1) {
                addNodeError(
                        errors,
                        "GRAPH_NODE_DISCONNECTED",
                        graphPath,
                        nodeId,
                        "非START/END节点必须同时存在入边和出边"
                );
            }

            if (node.getType() == GraphNodeType.MERGE
                    && inputCount < 2) {

                addNodeError(
                        errors,
                        "GRAPH_MERGE_INPUT_INSUFFICIENT",
                        graphPath,
                        nodeId,
                        "MERGE节点至少需要两条入边"
                );
            }

            if (node.getType() == GraphNodeType.FOREACH
                    && outputCount != 1) {

                addNodeError(
                        errors,
                        "GRAPH_FOREACH_OUTPUT_INVALID",
                        graphPath,
                        nodeId,
                        "FOREACH节点必须只有一条出边"
                );
            }
        });
    }

    private List<String> buildTopologicalOrder(
            Map<String, GraphNodeSpec> nodes,
            Map<String, List<GraphEdgeSpec>> incoming,
            Map<String, List<GraphEdgeSpec>> outgoing,
            String graphPath,
            List<GraphValidationError> errors) {

        Map<String, Integer> degree =
                new LinkedHashMap<>();

        nodes.keySet().forEach(nodeId ->
                degree.put(
                        nodeId,
                        incoming.get(nodeId).size()
                )
        );

        Deque<String> queue =
                new ArrayDeque<>();

        degree.forEach((nodeId, value) -> {
            if (value == 0) {
                queue.addLast(nodeId);
            }
        });

        List<String> order =
                new ArrayList<>();

        while (!queue.isEmpty()) {
            String nodeId =
                    queue.removeFirst();

            order.add(nodeId);

            for (GraphEdgeSpec edge :
                    outgoing.get(nodeId)) {

                String target = edge.getTarget();

                int nextDegree =
                        degree.compute(
                                target,
                                (key, value) ->
                                        value - 1
                        );

                if (nextDegree == 0) {
                    queue.addLast(target);
                }
            }
        }

        if (order.size() != nodes.size()) {
            addGraphError(
                    errors,
                    "GRAPH_CYCLE_DETECTED",
                    graphPath,
                    "GraphSpec中存在循环依赖"
            );
        }

        return order;
    }

    private void validateReachability(
            Map<String, GraphNodeSpec> nodes,
            Map<String, List<GraphEdgeSpec>> outgoing,
            Map<String, List<GraphEdgeSpec>> incoming,
            String startNodeId,
            String endNodeId,
            String graphPath,
            List<GraphValidationError> errors) {

        if (startNodeId == null || endNodeId == null) {
            return;
        }

        Set<String> reachableFromStart =
                walkForward(
                        startNodeId,
                        outgoing
                );

        Set<String> canReachEnd =
                walkBackward(
                        endNodeId,
                        incoming
                );

        for (String nodeId : nodes.keySet()) {
            if (!reachableFromStart.contains(nodeId)) {
                addNodeError(
                        errors,
                        "GRAPH_NODE_UNREACHABLE_FROM_START",
                        graphPath,
                        nodeId,
                        "节点无法从START到达"
                );
            }

            if (!canReachEnd.contains(nodeId)) {
                addNodeError(
                        errors,
                        "GRAPH_NODE_CANNOT_REACH_END",
                        graphPath,
                        nodeId,
                        "节点无法到达END"
                );
            }
        }
    }

    private Set<String> walkForward(
            String start,
            Map<String, List<GraphEdgeSpec>> outgoing) {

        Set<String> visited =
                new LinkedHashSet<>();

        Deque<String> queue =
                new ArrayDeque<>();

        queue.add(start);

        while (!queue.isEmpty()) {
            String current =
                    queue.removeFirst();

            if (!visited.add(current)) {
                continue;
            }

            for (GraphEdgeSpec edge :
                    outgoing.getOrDefault(
                            current,
                            List.of())) {

                queue.addLast(edge.getTarget());
            }
        }

        return visited;
    }

    private Set<String> walkBackward(
            String end,
            Map<String, List<GraphEdgeSpec>> incoming) {

        Set<String> visited =
                new LinkedHashSet<>();

        Deque<String> queue =
                new ArrayDeque<>();

        queue.add(end);

        while (!queue.isEmpty()) {
            String current =
                    queue.removeFirst();

            if (!visited.add(current)) {
                continue;
            }

            for (GraphEdgeSpec edge :
                    incoming.getOrDefault(
                            current,
                            List.of())) {

                queue.addLast(edge.getSource());
            }
        }

        return visited;
    }

    private void validateConfiguredValue(
            Object value,
            String graphPath,
            String nodeId,
            List<GraphValidationError> errors) {

        if (value instanceof String text
                && text.startsWith("$")) {

            validateExpression(
                    text,
                    graphPath,
                    nodeId,
                    "inputMapping",
                    errors
            );
            return;
        }

        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child ->
                    validateConfiguredValue(
                            child,
                            graphPath,
                            nodeId,
                            errors
                    )
            );
            return;
        }

        if (value instanceof Collection<?> collection) {
            collection.forEach(child ->
                    validateConfiguredValue(
                            child,
                            graphPath,
                            nodeId,
                            errors
                    )
            );
        }
    }

    private void validateExpression(
            String expression,
            String graphPath,
            String nodeId,
            String fieldName,
            List<GraphValidationError> errors) {

        if (!StringUtils.hasText(expression)
                || !EXPRESSION_PATTERN
                .matcher(expression.trim())
                .matches()) {

            addNodeError(
                    errors,
                    "GRAPH_EXPRESSION_INVALID",
                    graphPath,
                    nodeId,
                    fieldName +
                            "不是合法表达式：" +
                            expression
            );
        }
    }

    private Map<String, List<GraphEdgeSpec>>
    initializeAdjacency(
            Collection<String> nodeIds) {

        Map<String, List<GraphEdgeSpec>> result =
                new LinkedHashMap<>();

        nodeIds.forEach(nodeId ->
                result.put(
                        nodeId,
                        new ArrayList<>()
                )
        );

        return result;
    }

    private String safeMessage(
            JsonProcessingException exception) {

        String message =
                exception.getOriginalMessage();

        if (!StringUtils.hasText(message)) {
            return "配置格式不正确";
        }

        return message.length() <= 200
                ? message
                : message.substring(0, 200);
    }

    private void addGraphError(
            List<GraphValidationError> errors,
            String code,
            String graphPath,
            String message) {

        errors.add(
                new GraphValidationError(
                        code,
                        graphPath,
                        null,
                        null,
                        message
                )
        );
    }

    private void addNodeError(
            List<GraphValidationError> errors,
            String code,
            String graphPath,
            String nodeId,
            String message) {

        errors.add(
                new GraphValidationError(
                        code,
                        graphPath,
                        nodeId,
                        null,
                        message
                )
        );
    }

    private void addEdgeError(
            List<GraphValidationError> errors,
            String code,
            String graphPath,
            String edgeId,
            String message) {

        errors.add(
                new GraphValidationError(
                        code,
                        graphPath,
                        null,
                        edgeId,
                        message
                )
        );
    }

    private static class CompilationCounter {

        private int totalNodes;

        private boolean totalLimitReported;
    }
}