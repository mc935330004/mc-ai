package org.example.ai.agent.graph.runtime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 嵌套FOREACH结果识别器。
 *
 * 支持识别以下形式：
 * 1. 直接返回ForEachBatchResult；
 * 2. END节点返回$vars.xxx整个变量信封；
 * 3. GraphNodeResult和GraphExecutionResult包装的批量结果。
 *
 * 不遍历普通业务对象的任意字段，
 * 防止把业务接口中的同名字段误识别为工作流结果。
 */
public final class ForEachNestedResultInspector {

    /**
     * 限制变量信封递归深度，防止异常循环引用。
     */
    private static final int MAX_ENVELOPE_DEPTH = 6;

    /**
     * 只检查工作流运行时定义的标准信封字段。
     */
    private static final List<String> ENVELOPE_KEYS =
            List.of(
                    "workflowData",
                    "data",
                    "result"
            );

    private ForEachNestedResultInspector() {
    }

    /**
     * 从运行结果中查找FOREACH批量结果。
     */
    public static Optional<ForEachBatchResult> findBatch(
            Object value) {

        return findBatch(
                value,
                0,
                new IdentityHashMap<>()
        );
    }

    private static Optional<ForEachBatchResult> findBatch(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> visited) {

        if (value == null
                || depth > MAX_ENVELOPE_DEPTH) {
            return Optional.empty();
        }

        if (value instanceof ForEachBatchResult batch) {
            return Optional.of(batch);
        }

        /*
         * 防止Map或运行结果对象存在循环引用。
         */
        if (visited.put(value, Boolean.TRUE) != null) {
            return Optional.empty();
        }

        if (value instanceof GraphExecutionResult graphResult) {
            return findBatch(
                    graphResult.result(),
                    depth + 1,
                    visited
            );
        }

        if (value instanceof GraphNodeResult nodeResult) {
            Optional<ForEachBatchResult> workflowBatch =
                    findBatch(
                            nodeResult.workflowData(),
                            depth + 1,
                            visited
                    );

            if (workflowBatch.isPresent()) {
                return workflowBatch;
            }

            return findBatch(
                    nodeResult.data(),
                    depth + 1,
                    visited
            );
        }

        if (value instanceof Map<?, ?> map) {
            for (String key : ENVELOPE_KEYS) {
                Optional<ForEachBatchResult> batch =
                        findBatch(
                                map.get(key),
                                depth + 1,
                                visited
                        );

                if (batch.isPresent()) {
                    return batch;
                }
            }
        }

        return Optional.empty();
    }
}