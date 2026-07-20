package org.example.ai.agent.workflow.snapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.GraphSpecParser;
import org.example.ai.agent.graph.compiler.*;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.graph.model.GraphSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 工作流GraphSpec快照工厂。
 *
 * 职责：
 * 1. 解析GraphSpec；
 * 2. 校验工作流稳定编码；
 * 3. 调用GraphSpecCompiler；
 * 4. 生成字段顺序稳定的JSON；
 * 5. 生成SHA-256；
 * 6. 统计主图和FOREACH子图节点数量。
 */
@Component
@RequiredArgsConstructor
public class WorkflowGraphSnapshotFactory {

    private final ObjectMapper objectMapper;
    private final GraphSpecParser graphSpecParser;
    private final GraphSpecCompiler graphSpecCompiler;

    /**
     * 分析工作流草稿。
     *
     * 草稿允许拓扑校验失败，但编码和名称必须与主表一致。
     */
    public WorkflowGraphMaterial analyzeDraft(
            String expectedCode,
            String expectedName,
            String graphSpecJson) {

        GraphSpec graph = graphSpecParser.parse(graphSpecJson);

        if (!Objects.equals(
                expectedCode,
                trimToNull(graph.getCode())
        )) {
            throw new BusinessException(
                    400,
                    "GraphSpec.code必须与workflowCode一致"
            );
        }

        if (!Objects.equals(
                expectedName,
                trimToNull(graph.getName())
        )) {
            throw new BusinessException(
                    400,
                    "GraphSpec.name必须与workflowName一致"
            );
        }

        /*
         * 去掉编码和名称两端空格，保证校验和稳定。
         */
        graph.setCode(expectedCode);
        graph.setName(expectedName);

        return analyze(graph);
    }

    /**
     * 分析历史发布快照。
     *
     * 只校验稳定编码，不使用主表中的最新名称。
     * 因为用户编辑草稿名称时，不能破坏旧发布版本运行。
     */
    public WorkflowGraphMaterial analyzePublished(
            String expectedCode,
            String snapshotJson) {

        GraphSpec graph = graphSpecParser.parse(snapshotJson);

        if (!Objects.equals(
                expectedCode,
                trimToNull(graph.getCode())
        )) {
            throw new IllegalStateException(
                    "工作流发布快照编码不一致：" +
                            expectedCode
            );
        }

        return analyze(graph);
    }

    private WorkflowGraphMaterial analyze(
            GraphSpec graph) {

        GraphCompilationResult graphCompilation =
                graphSpecCompiler.compile(graph);
        List<GraphValidationError> errors =
                new ArrayList<>(
                        graphCompilation.errors()
                );
        validateInputSchema(graph.getInputSchema(),errors );

        GraphCompilationResult compilation =
                errors.isEmpty()
                        ? graphCompilation
                        : GraphCompilationResult.failure(
                        errors
                );
        JsonNode canonicalNode = canonicalize(
                objectMapper.valueToTree(graph)
        );

        String normalizedJson = writeJson(
                canonicalNode
        );

        GraphSize graphSize;

        if (compilation.valid()) {
            graphSize = countGraph(
                    compilation.compiledGraph()
            );
        } else {
            graphSize = new GraphSize(
                    graph.getNodes() == null
                            ? 0
                            : graph.getNodes().size(),
                    graph.getEdges() == null
                            ? 0
                            : graph.getEdges().size()
            );
        }

        return new WorkflowGraphMaterial(
                normalizedJson,
                sha256(normalizedJson),
                compilation,
                graphSize.nodeCount(),
                graphSize.edgeCount()
        );
    }

    /**
     * 计算包含FOREACH子图在内的节点和连线总数。
     */
    private GraphSize countGraph(
            CompiledGraphSpec graph) {

        int nodeCount = graph.nodesById().size();
        int edgeCount = graph.edges().size();

        for (CompiledGraphNode node :
                graph.nodesById().values()) {

            if (node.config()
                    instanceof CompiledForEachNodeConfig forEach
                    && forEach.body() != null) {

                GraphSize child = countGraph(
                        forEach.body()
                );

                nodeCount += child.nodeCount();
                edgeCount += child.edgeCount();
            }
        }

        return new GraphSize(
                nodeCount,
                edgeCount
        );
    }

    /**
     * 校验数据库中的快照原文是否被修改。
     */
    public String checksumRaw(String json) {
        if (!StringUtils.hasText(json)) {
            throw new IllegalArgumentException(
                    "待计算校验和的JSON不能为空"
            );
        }

        return sha256(json);
    }

    /**
     * 对JSON对象字段递归排序。
     *
     * 数组顺序保持不变，因为节点、映射和配置数组顺序可能有业务含义。
     */
    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }

        if (node.isObject()) {
            ObjectNode sorted =
                    objectMapper.createObjectNode();

            List<String> fieldNames =
                    new ArrayList<>();

            node.fieldNames()
                    .forEachRemaining(
                            fieldNames::add
                    );

            Collections.sort(fieldNames);

            for (String fieldName : fieldNames) {
                sorted.set(
                        fieldName,
                        canonicalize(
                                node.get(fieldName)
                        )
                );
            }

            return sorted;
        }

        if (node.isArray()) {
            ArrayNode array =
                    objectMapper.createArrayNode();

            for (JsonNode child : node) {
                array.add(canonicalize(child));
            }

            return array;
        }

        return node.deepCopy();
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(
                    node
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "GraphSpec序列化失败",
                    exception
            );
        }
    }

    private String sha256(String source) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance(
                            "SHA-256"
                    );

            byte[] bytes = digest.digest(
                    source.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            return HexFormat.of()
                    .formatHex(bytes);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Java运行环境不支持SHA-256",
                    exception
            );
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

    private record GraphSize(
            int nodeCount,
            int edgeCount) {
    }

    /**
     * 校验工作流对外输入契约。
     *
     * 这里只检查Schema结构；
     * 用户真实输入由WorkflowExecutionFacade再次校验。
     */
    private void validateInputSchema(
            JsonNode inputSchema,
            List<GraphValidationError> errors) {

        if (inputSchema == null
                || inputSchema.isNull()) {

            errors.add(
                    new GraphValidationError(
                            "WORKFLOW_INPUT_SCHEMA_REQUIRED",
                            "root",
                            null,
                            null,
                            "工作流必须配置inputSchema"
                    )
            );

            return;
        }

        if (!inputSchema.isObject()) {
            errors.add(new GraphValidationError(
                            "WORKFLOW_INPUT_SCHEMA_INVALID",
                            "root",
                            null,
                            null,
                            "工作流inputSchema必须是JSON对象"
                    ) );
            return;
        }

        String rootType =inputSchema.path("type").asText("");

        if (StringUtils.hasText(rootType)&& !"object".equals(rootType)) {

            errors.add(new GraphValidationError(
                            "WORKFLOW_INPUT_SCHEMA_ROOT_INVALID",
                            "root",
                            null,
                            null,
                            "工作流inputSchema根节点必须是object"
                    ));
        }

        JsonNode properties = inputSchema.path("properties");

        if (!properties.isObject()) {
            errors.add(new GraphValidationError(
                            "WORKFLOW_INPUT_SCHEMA_PROPERTIES_REQUIRED",
                            "root",
                            null,
                            null,
                            "工作流inputSchema必须配置properties对象"
                    )
            );
        }
    }
    /**
     * 从发布快照读取输入Schema。
     */
    public JsonNode readInputSchema(String snapshotJson) {

        GraphSpec graph = graphSpecParser.parse(snapshotJson);

        JsonNode inputSchema =graph.getInputSchema();

        if (inputSchema == null || !inputSchema.isObject()) {
            throw new IllegalStateException(
                    "已发布工作流缺少有效inputSchema：" +
                            graph.getCode()
            );
        }

        return inputSchema.deepCopy();
    }
}