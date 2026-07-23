package org.example.ai.agent.graph.runtime;

import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次GraphSpec运行的内部上下文。
 */
public class GraphExecutionContext {

    private final String runId;
    private final String userId;
    private final Map<String, Object> input;
    private final Map<String, Object> userContext;
    private final String authorization;
    private final Map<String, Object> secureContext;
    private final Object currentItem;
    private final String executionPath;
    /**
     * 当前图拥有的祖先FOREACH数量。
     */
    private final int forEachDepth;

    /**
     * 不同并行分支会同时写入不同outputKey。
     */
    private final Map<String, Object> variables =new ConcurrentHashMap<>();

    private GraphExecutionContext(
            GraphExecutionRequest request) {

        this.runId = request.getRunId();
        this.userId = request.getUserId();
        this.input = readOnlyCopy(
                request.getInput()
        );
        this.userContext = readOnlyCopy(
                request.getUserContext()
        );
        this.authorization =
                request.getAuthorization();
        this.secureContext = readOnlyCopy(
                request.getSecureContext()
        );
        this.currentItem =
                request.getCurrentItem();
        if (request.getForEachDepth() < 0) {
            throw new GraphExecutionException(
                    "GRAPH_FOREACH_DEPTH_INVALID",
                    "FOREACH执行深度不能小于0"
            );
        }

        this.forEachDepth =
                request.getForEachDepth();
        this.executionPath =
                StringUtils.hasText(
                        request.getExecutionPath()
                )
                        ? request.getExecutionPath()
                        : "root";
        /*
         * 每个子图上下文复制父变量快照。
         *
         * 这里只复制Map容器；
         * 信封本身已经是不可变Map，不需要重复深拷贝。
         */
        if (request.getInitialVariables() != null) {
            request.getInitialVariables()
                    .forEach((key, value) -> {
                        if (StringUtils.hasText(key) && value != null) {
                            variables.put(key, value);
                        }
                    });
        }

    }

    public static GraphExecutionContext create(
            GraphExecutionRequest request) {

        if (request == null) {
            throw new GraphExecutionException(
                    "GRAPH_REQUEST_REQUIRED",
                    "Graph执行请求不能为空"
            );
        }

        return new GraphExecutionContext(request);
    }


    public void publish(
            String outputKey,
            GraphNodeResult result) {

        if (!StringUtils.hasText(outputKey)
                || result == null) {
            return;
        }

        variables.put(
                outputKey,
                result.toVariableEnvelope()
        );
    }

    public Map<String, Object> snapshotVariables() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(variables)
        );
    }
    /**
     * 获取当前图的祖先FOREACH数量。
     */
    public int getForEachDepth() {
        return forEachDepth;
    }

    public String getRunId() {
        return runId;
    }

    public String getUserId() {
        return userId;
    }

    public Map<String, Object> getInput() {
        return input;
    }

    public Map<String, Object> getUserContext() {
        return userContext;
    }

    public String getAuthorization() {
        return authorization;
    }

    public Map<String, Object> getSecureContext() {
        return secureContext;
    }

    public Object getCurrentItem() {
        return currentItem;
    }
    public String getExecutionPath() {
        return executionPath;
    }
    private Map<String, Object> readOnlyCopy(
            Map<String, Object> source) {

        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        return Collections.unmodifiableMap(
                new LinkedHashMap<>(source)
        );
    }

}