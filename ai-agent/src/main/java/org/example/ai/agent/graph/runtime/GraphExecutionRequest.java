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
     * 当前图拥有的祖先FOREACH数量。
     *
     * root图为0；
     * 外层项目循环body为1；
     * 内层业务记录循环body为2。
     */
    @Builder.Default
    private int forEachDepth = 0;

    /**
     * 是否在当前线程同步执行子图节点。
     *
     * FOREACH项目任务已经运行在隔离线程池中，
     * 子图再提交到graphRuntimeExecutor会产生循环等待风险，
     * 因此FOREACH子图统一使用同步执行。
     */
    @Builder.Default
    private boolean inlineExecution = false;

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