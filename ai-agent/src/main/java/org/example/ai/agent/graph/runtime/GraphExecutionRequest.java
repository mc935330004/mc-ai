package org.example.ai.agent.graph.runtime;

import lombok.Builder;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphSpec执行请求。
 *
 * 不使用@Data，避免生成包含authorization的toString。
 */
@Getter
@Builder
public class GraphExecutionRequest {

    private String runId;

    private String userId;

    @Builder.Default
    private Map<String, Object> input = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> userContext = new LinkedHashMap<>();

    private String authorization;

    @Builder.Default
    private Map<String, Object> secureContext = new LinkedHashMap<>();

    /**
     * L1-3执行ForEach子图时使用。
     */
    private Object currentItem;

    /**
     * 子图启动时继承的父图变量快照。
     *
     * 每个FOREACH项目都会获得独立副本。
     */
    @Builder.Default
    private Map<String, Object> initialVariables =new LinkedHashMap<>();

    /**
     * 当前执行路径，用于运行轨迹定位。
     *
     * 示例：
     * root/project_loop[2]
     */
    @Builder.Default
    private String executionPath = "root";
}