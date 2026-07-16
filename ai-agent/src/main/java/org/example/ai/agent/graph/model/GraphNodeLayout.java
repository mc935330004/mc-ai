package org.example.ai.agent.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 前端工作流画布布局。
 *
 * 本阶段不开发前端，但提前保留坐标协议。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class GraphNodeLayout {

    private Double x;

    private Double y;

    private Double width;

    private Double height;
}