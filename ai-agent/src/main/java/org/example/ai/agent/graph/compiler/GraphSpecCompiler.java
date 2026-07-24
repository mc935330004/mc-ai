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
import org.example.ai.agent.graph.GraphSpecLimits;

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

    /**
     * 工作流业务状态码格式。
     *
     * 示例：
     * SKIPPED_NO_ID
     * SKIPPED_MISSING_PROJECT_CODE
     */
    private static final Pattern RESULT_CODE_PATTERN =
            Pattern.compile(
                    "^[A-Z][A-Z0-9_]{2,63}$"
            );
    /**
     * 能力请求参数路径格式。
     *
     * 支持：
     * current
     * size
     * page.current
     *
     * 不支持：
     * SpEL、方法调用、数组通配符和脚本表达式。
     */
    private static final Pattern PAGINATION_INPUT_PATH_PATTERN =
            Pattern.compile("^[\\p{L}_][\\p{L}\\p{N}_-]*" +
                            "(?:\\.[\\p{L}\\p{N}_-]+)*$");

    /**
     * 分页响应数据的受限 JSON 路径。
     *
     * 支持：
     * $.records
     * $.data.records
     * $.total
     *
     * 不支持：
     * $..records
     * $.records[?()]
     * 脚本、过滤器和递归查询。
     */
    private static final Pattern PAGINATION_RESPONSE_PATH_PATTERN =
            Pattern.compile("^\\$(?:\\.[\\p{L}\\p{N}_-]+)+$");
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
        /*
         * WRITE 工作流第一版只允许：
         *
         * START → WRITE → END
         *
         * 这里只做编译校验，不执行写接口。
         */
        validateWriteTopology(
                nodeMap,
                compiledConfigs,
                incoming,
                outgoing,
                startNodeId,
                endNodeId,
                rootGraph,
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
    /**
     * 校验第一版 WRITE 工作流结构。
     *
     * 当前只允许一个末端 WRITE 节点，
     * 不支持查询后写入、循环写入或多个写节点。
     */
    private void validateWriteTopology(
            Map<String, GraphNodeSpec> nodes,
            Map<String, GraphNodeConfig> configs,
            Map<String, List<GraphEdgeSpec>> incoming,
            Map<String, List<GraphEdgeSpec>> outgoing,
            String startNodeId,
            String endNodeId,
            boolean rootGraph,
            String graphPath,
            List<GraphValidationError> errors) {

        List<String> writeNodeIds =
                new ArrayList<>();

        for (Map.Entry<String, GraphNodeConfig> entry
                : configs.entrySet()) {

            if (!(entry.getValue()
                    instanceof CapabilityNodeConfig config)) {

                continue;
            }

            String sideEffect;

            try {
                sideEffect =
                        capabilityCatalog.sideEffect(
                                config.capabilityCode()
                        );
            } catch (Exception exception) {
                addNodeError(
                        errors,
                        "GRAPH_CAPABILITY_SIDE_EFFECT_UNAVAILABLE",
                        graphPath,
                        entry.getKey(),
                        "能力副作用信息暂时不可用"
                );
                return;
            }

            if ("WRITE".equalsIgnoreCase(sideEffect)) {
                writeNodeIds.add(entry.getKey());
            }
        }

        // 没有 WRITE 节点时，保持原查询工作流逻辑。
        if (writeNodeIds.isEmpty()) {
            return;
        }

        /*
         * 第一版不允许在 FOREACH 子图中写入，
         * 也不允许一个流程包含多个 WRITE 节点。
         */
        if (!rootGraph || writeNodeIds.size() != 1) {
            addGraphError(
                    errors,
                    "GRAPH_WRITE_TOPOLOGY_INVALID",
                    graphPath,
                    "WRITE工作流只能包含一个根级WRITE节点"
            );
            return;
        }

        String writeNodeId =
                writeNodeIds.get(0);

        GraphNodeConfig nodeConfig =
                configs.get(writeNodeId);

        /*
         * WRITE 节点禁止开启自动分页。
         */
        if (nodeConfig
                instanceof CapabilityNodeConfig capabilityConfig
                && capabilityConfig.pagination() != null
                && capabilityConfig.pagination().isEnabled()) {

            addNodeError(
                    errors,
                    "GRAPH_WRITE_PAGINATION_NOT_ALLOWED",
                    graphPath,
                    writeNodeId,
                    "WRITE节点不能配置自动分页"
            );
        }

        List<GraphEdgeSpec> writeIncoming =
                incoming.getOrDefault(
                        writeNodeId,
                        List.of()
                );

        List<GraphEdgeSpec> writeOutgoing =
                outgoing.getOrDefault(
                        writeNodeId,
                        List.of()
                );

        boolean valid =
                nodes.size() == 3
                        && startNodeId != null
                        && endNodeId != null
                        && writeIncoming.size() == 1
                        && writeOutgoing.size() == 1
                        && startNodeId.equals(
                        writeIncoming.get(0).getSource()
                )
                        && endNodeId.equals(
                        writeOutgoing.get(0).getTarget()
                );

        if (!valid) {
            addNodeError(
                    errors,
                    "GRAPH_WRITE_TOPOLOGY_INVALID",
                    graphPath,
                    writeNodeId,
                    "WRITE工作流只允许START → WRITE → END"
            );
        }
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

        boolean callable = false;

        try {
            callable = capabilityCatalog.isCallable(
                    config.capabilityCode()
            );

            if (!callable) {
                addNodeError(
                        errors,
                        "GRAPH_CAPABILITY_NOT_CALLABLE",
                        graphPath,
                        node.getId(),
                        "能力不存在、未启用或未发布："
                                + config.capabilityCode()
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

        /*
         * 只有能力可调用时才查询输入契约，
         * 避免对同一个无效能力产生重复错误。
         */
        if (callable) {
            validateCapabilityContract(
                    node,
                    config,
                    graphPath,
                    errors
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

    /**
     * 读取能力输入契约并校验：
     * 1. 普通 inputMapping；
     * 2. 自动分页参数；
     * 3. 分页参数是否确实存在于能力 Schema。
     */
    private void validateCapabilityContract( GraphNodeSpec node, CapabilityNodeConfig config,
                                             String graphPath,List<GraphValidationError> errors) {
        try {
            Optional<GraphCapabilityContract> contractOptional =capabilityCatalog.findContract(config.capabilityCode());

            /*
             * 普通能力可以兼容旧目录实现。
             *
             * 但自动分页必须拥有明确的能力请求契约，
             * 否则无法判断 current、size 是否为真实接口字段。
             */
            if (contractOptional.isEmpty()) {

                if (config.pagination() != null && config.pagination().isEnabled()) {

                    addNodeError(errors,
                            "GRAPH_PAGINATION_CONTRACT_REQUIRED",
                            graphPath,
                            node.getId(),
                            "开启自动分页的能力必须提供请求参数契约"
                    );
                }
                return;
            }
            GraphCapabilityContract contract =contractOptional.get();

            validateCapabilityInputMapping(
                    node,
                    config,
                    contract,
                    graphPath,
                    errors
            );
            validateCapabilityPagination(
                    node,
                    config,
                    contract,
                    graphPath,
                    errors
            );

        } catch (Exception exception) {
            /*
             * 能力契约读取异常必须失败关闭，
             * 不能跳过校验后发布工作流。
             */
            addNodeError(
                    errors,
                    "GRAPH_CAPABILITY_CONTRACT_UNAVAILABLE",
                    graphPath,
                    node.getId(),
                    "能力输入契约暂时不可用："
                            + config.capabilityCode()
            );
        }
    }
    /**
     * 校验能力节点的自动分页配置。
     */
    private void validateCapabilityPagination(
            GraphNodeSpec node,
            CapabilityNodeConfig config,
            GraphCapabilityContract contract,
            String graphPath,
            List<GraphValidationError> errors) {

        CapabilityPaginationConfig pagination =
                config.pagination();

        /*
         * 没有开启分页时完全保持原来的单次能力调用逻辑。
         */
        if (pagination == null
                || !pagination.isEnabled()) {
            return;
        }

        boolean pageNumberValid =
                validatePaginationInputPath(
                        node,
                        config,
                        contract,
                        pagination.pageNumberInputPath(),
                        "pageNumberInputPath",
                        graphPath,
                        errors
                );

        boolean pageSizeValid =
                validatePaginationInputPath(
                        node,
                        config,
                        contract,
                        pagination.pageSizeInputPath(),
                        "pageSizeInputPath",
                        graphPath,
                        errors
                );

        /*
         * 页码字段和每页数量字段不能使用同一个请求参数。
         */
        if (pageNumberValid
                && pageSizeValid
                && pagination.pageNumberInputPath()
                .equals(pagination.pageSizeInputPath())) {

            addNodeError(
                    errors,
                    "GRAPH_PAGINATION_INPUT_DUPLICATED",
                    graphPath,
                    node.getId(),
                    "页码参数和每页数量参数不能使用同一个字段"
            );
        }

        validatePaginationResponsePath(
                node,
                pagination.recordsPath(),
                "recordsPath",
                true,
                graphPath,
                errors
        );

        validatePaginationResponsePath(
                node,
                pagination.totalPath(),
                "totalPath",
                false,
                graphPath,
                errors
        );

        if (StringUtils.hasText(pagination.recordsPath())
                && StringUtils.hasText(pagination.totalPath())
                && pagination.recordsPath()
                .equals(pagination.totalPath())) {

            addNodeError(
                    errors,
                    "GRAPH_PAGINATION_RESPONSE_PATH_DUPLICATED",
                    graphPath,
                    node.getId(),
                    "recordsPath 和 totalPath 不能相同"
            );
        }
    }

    /**
     * 校验分页请求参数。
     *
     * 分页字段必须同时满足：
     * 1. 字段格式合法；
     * 2. 能力请求参数 Schema 已声明；
     * 3. 当前节点 inputMapping 已配置。
     */
    private boolean validatePaginationInputPath(
            GraphNodeSpec node,
            CapabilityNodeConfig config,
            GraphCapabilityContract contract,
            String inputPath,
            String configName,
            String graphPath,
            List<GraphValidationError> errors) {

        if (!StringUtils.hasText(inputPath)) {

            addNodeError(
                    errors,
                    "GRAPH_PAGINATION_PAGE_INPUT_REQUIRED",
                    graphPath,
                    node.getId(),
                    configName + "不能为空"
            );

            return false;
        }

        String normalizedPath =
                inputPath.trim();

        if (!PAGINATION_INPUT_PATH_PATTERN
                .matcher(normalizedPath)
                .matches()) {

            addNodeError(
                    errors,
                    "GRAPH_PAGINATION_INPUT_PATH_INVALID",
                    graphPath,
                    node.getId(),
                    "分页请求参数路径格式不正确："
                            + normalizedPath
            );

            return false;
        }

        /*
         * 这是防止凭空增加 current、size 等字段的关键校验。
         */
        if (!contract.allowedInputPaths()
                .contains(normalizedPath)) {

            addNodeError(
                    errors,
                    "GRAPH_PAGINATION_INPUT_NOT_DECLARED",
                    graphPath,
                    node.getId(),
                    "分页参数未在能力请求参数Schema中声明："
                            + normalizedPath
            );

            return false;
        }

        /*
         * P1-2 分页执行器会读取第一次调用的页码和分页大小，
         * 因此 inputMapping 必须配置对应字段。
         */
        if (!containsMappingPath(
                config.inputMapping(),
                normalizedPath)) {

            addNodeError(
                    errors,
                    "GRAPH_PAGINATION_INPUT_MAPPING_REQUIRED",
                    graphPath,
                    node.getId(),
                    "自动分页参数尚未配置输入映射："
                            + normalizedPath
            );

            return false;
        }

        return true;
    }

    /**
     * 校验分页响应数据路径。
     */
    private void validatePaginationResponsePath( GraphNodeSpec node,
            String responsePath,
            String configName,
            boolean required,
            String graphPath,
            List<GraphValidationError> errors) {

        if (!StringUtils.hasText(responsePath)) {
            if (required) {
                addNodeError(
                        errors,
                        "GRAPH_PAGINATION_RECORDS_PATH_REQUIRED",
                        graphPath,
                        node.getId(),
                        configName + "不能为空"
                );
            }
            /*
             * totalPath 允许不配置。
             *
             * P1-2 中未配置 totalPath 时，
             * 使用“当前页数量小于 size”作为结束条件。
             */
            return;
        }

        String normalizedPath =responsePath.trim();

        if (!PAGINATION_RESPONSE_PATH_PATTERN
                .matcher(normalizedPath)
                .matches()) {
            addNodeError(
                    errors,
                    "GRAPH_PAGINATION_RESPONSE_PATH_INVALID",
                    graphPath,
                    node.getId(),
                    configName + "必须使用受限JSON路径："
                            + normalizedPath
            );
        }
    }

    /**
     * 校验工作流inputMapping只包含能力允许的字段，
     * 并检查能力必填输入是否已经配置。
     */
    private void validateCapabilityInputMapping(
            GraphNodeSpec node,
            CapabilityNodeConfig config,
            GraphCapabilityContract contract,
            String graphPath,
            List<GraphValidationError> errors) {

        validateMappingObject(
                config.inputMapping(),
                "",
                contract.allowedInputPaths(),
                graphPath,
                node.getId(),
                errors
        );

        for (String requiredPath : contract.requiredInputPaths()) {

            if (!containsMappingPath(config.inputMapping(), requiredPath)) {
                addNodeError(
                        errors,
                        "GRAPH_CAPABILITY_INPUT_REQUIRED",
                        graphPath,
                        node.getId(),
                        "能力必填输入未配置："
                                + requiredPath
                );
            }
        }
    }

    /**
     * 递归校验嵌套输入映射。
     *
     * 示例：
     *
     * {
     *   "project": {
     *     "name": "$input.projectName"
     *   }
     * }
     */
    private void validateMappingObject(
            Map<String, Object> mapping,
            String prefix,
            Set<String> allowedPaths,
            String graphPath,
            String nodeId,
            List<GraphValidationError> errors) {

        for (Map.Entry<String, Object> entry: mapping.entrySet()) {
            String key = entry.getKey();
            if (!StringUtils.hasText(key)) {
                /*
                 * 顶层空名称已经由原有校验处理；
                 * 嵌套空名称在这里统一拦截。
                 */
                addNodeError( errors,
                        "GRAPH_INPUT_NAME_INVALID",
                        graphPath,
                        nodeId,
                        "输入参数名称不能为空"
                );
                continue;
            }

            String path =StringUtils.hasText(prefix)
                            ? prefix + "." + key
                            : key;

            boolean exactAllowed =allowedPaths.contains(path);

            boolean knownParent =
                    allowedPaths.stream()
                            .anyMatch(allowedPath ->
                                    allowedPath.startsWith(
                                            path + "."
                                    )
                            );

            if (!exactAllowed && !knownParent) {
                addNodeError(
                        errors,
                        "GRAPH_CAPABILITY_INPUT_UNKNOWN",
                        graphPath,
                        nodeId,
                        "能力输入映射包含未声明字段："
                                + path
                );

                continue;
            }
            Object value = entry.getValue();

            /*
             * 当前路径存在子字段并且值是对象时，
             * 继续校验对象内部字段。
             */
            if (knownParent && value instanceof Map<?, ?> childMap) {
                Map<String, Object> normalizedChild =new LinkedHashMap<>();

                childMap.forEach((childKey, childValue) -> {
                    if (childKey != null) {
                        normalizedChild.put(
                                String.valueOf(childKey),
                                childValue
                        );
                    }
                });
                validateMappingObject(
                        normalizedChild,
                        path,
                        allowedPaths,
                        graphPath,
                        nodeId,
                        errors
                );
                continue;
            }

            /*
             * 当前路径只是某个允许字段的父路径，
             * 但配置值不是对象，无法生成正确的嵌套输入。
             */
            if (!exactAllowed && knownParent) {
                addNodeError(
                        errors,
                        "GRAPH_CAPABILITY_INPUT_STRUCTURE_INVALID",
                        graphPath,
                        nodeId,
                        "能力嵌套输入必须使用JSON对象配置："
                                + path
                );
            }
        }
    }

    /**
     * 判断inputMapping是否配置了指定必填路径。
     *
     * 如果某个父对象直接使用表达式整体映射，
     * 认为其内部字段将在运行时由Schema继续校验。
     */
    private boolean containsMappingPath(
            Map<String, Object> mapping,
            String path) {

        Object current = mapping;
        String[] pathParts = path.split("\\.");

        for (String pathPart : pathParts) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                /*
                 * 父对象已经整体映射，
                 * 编译期无法继续查看内部结构，
                 * 后续由CapabilityInputSchemaValidator校验。
                 */
                return current != null;
            }

            if (!currentMap.containsKey(pathPart)) {
                return false;
            }

            current = currentMap.get(pathPart);
        }

        /*
         * 必填字段不能显式映射为null。
         */
        return current != null;
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
        /*
         * 普通用户输入循环继续使用 maxItems 限制。
         *
         * 业务接口返回的 records 可以配置 processAllItems=true，
         * 此时不限制总记录数，但并发数仍然最多为 5。
         */
        boolean processAllItems =Boolean.TRUE.equals(config.processAllItems() );

        int maxItems =
                config.maxItems() == null
                        ? 5
                        : config.maxItems();

        if (!processAllItems && (maxItems < 1 || maxItems > 5)) {
            addNodeError(
                    errors,
                    "GRAPH_FOREACH_MAX_ITEMS_INVALID",
                    graphPath,
                    node.getId(),
                    "普通FOREACH的maxItems必须在1到5之间"
            );
        }

        int concurrency =
                config.concurrency() == null
                        ? Math.min(2, maxItems)
                        : config.concurrency();

        if (concurrency < 1|| concurrency > 5 || (!processAllItems && concurrency > maxItems)) {
            addNodeError(
                    errors,
                    "GRAPH_FOREACH_CONCURRENCY_INVALID",
                    graphPath,
                    node.getId(),
                    "FOREACH的concurrency必须在1到5之间，"
                            + "普通限量循环的并发数不能超过maxItems"
            );
        }

        boolean continueOnItemError =
                config.continueOnItemError() == null
                        || config.continueOnItemError();

        CompiledGraphSpec body = null;

        if (depth >= GraphSpecLimits.MAX_FOREACH_NESTING_DEPTH) {
            addNodeError(
                    errors,
                    "GRAPH_NESTING_DEPTH_EXCEEDED",
                    graphPath,
                    node.getId(),
                    "FOREACH最多允许嵌套" + GraphSpecLimits.MAX_FOREACH_NESTING_DEPTH
                            + "层：第一层处理项目，第二层处理业务记录"
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
        ForEachMissingValueSkip missingValueSkip = compileMissingValueSkip(
                        node,
                        config.missingValueSkip(),
                        graphPath,
                        errors );
        return new CompiledForEachNodeConfig(
                config.itemsExpression(),
                maxItems,
                concurrency,
                continueOnItemError,
                processAllItems,
                missingValueSkip,
                body
        );
    }

    /**
     * 编译并校验 FOREACH 缺值跳过策略。
     */
    private ForEachMissingValueSkip compileMissingValueSkip(
            GraphNodeSpec node,
            ForEachMissingValueSkip source,
            String graphPath,
            List<GraphValidationError> errors) {

        if (source == null) {
            return null;
        }

        String expression =
                StringUtils.hasText(source.expression())
                        ? source.expression().trim()
                        : null;

        /*
         * 继续复用 GraphSpec 现有受限表达式规则，
         * 禁止 SpEL、脚本和方法调用。
         */
        validateExpression(
                expression,
                graphPath,
                node.getId(),
                "missingValueSkip.expression",
                errors
        );

        String code =
                StringUtils.hasText(source.code())
                        ? source.code().trim()
                        : "SKIPPED_MISSING_VALUE";

        if (!RESULT_CODE_PATTERN.matcher(code).matches()) {
            addNodeError(
                    errors,
                    "GRAPH_FOREACH_SKIP_CODE_INVALID",
                    graphPath,
                    node.getId(),
                    "missingValueSkip.code格式不正确：" + code
            );
        }

        String message =
                StringUtils.hasText(source.message())
                        ? source.message().trim()
                        : "当前记录缺少必要值，已跳过子图执行";

        if (message.length() > 200) {
            addNodeError(
                    errors,
                    "GRAPH_FOREACH_SKIP_MESSAGE_TOO_LONG",
                    graphPath,
                    node.getId(),
                    "missingValueSkip.message最多允许200个字符"
            );
        }

        return new ForEachMissingValueSkip(
                expression,
                code,
                message
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