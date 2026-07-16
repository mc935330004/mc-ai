package org.example.ai.agent.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流图定义。
 *
 * 本对象属于可编辑的草稿模型，
 * 不能直接交给运行器执行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class GraphSpec {

    /**
     * GraphSpec协议版本。
     */
    @Builder.Default
    private String version = "1.0";

    /**
     * 工作流稳定编码。
     *
     * 嵌套ForEach子图可以为空。
     */
    private String code;

    /**
     * 工作流名称。
     *
     * 嵌套ForEach子图可以为空。
     */
    private String name;

    @Builder.Default
    private List<GraphNodeSpec> nodes =new ArrayList<>();

    @Builder.Default
    private List<GraphEdgeSpec> edges = new ArrayList<>();
    // 其余代码不变

    /**
     * 工作流对外输入JSON Schema。
     *
     * Planner提取出的输入和执行接口传入的输入，
     * 都必须经过该Schema校验。
     */
    private JsonNode inputSchema;
}