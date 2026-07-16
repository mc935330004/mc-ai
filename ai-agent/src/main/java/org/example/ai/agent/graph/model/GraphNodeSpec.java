package org.example.ai.agent.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.ai.agent.common.enums.GraphNodeType;

/**
 * GraphSpec节点草稿。
 *
 * config暂时使用JsonNode保存，
 * 编译阶段根据type转换成对应强类型配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class GraphNodeSpec {

    /**
     * 节点唯一编码。
     */
    private String id;

    private GraphNodeType type;

    private String name;

    private String description;

    /**
     * 节点输出写入变量池时使用的key。
     *
     * CAPABILITY、FOREACH、MERGE必须配置。
     */
    private String outputKey;

    /**
     * 不同节点的专属配置。
     */
    private JsonNode config;

    /**
     * 仅供前端画布使用，编译后不会进入运行模型。
     */
    private GraphNodeLayout layout;
}