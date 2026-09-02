package org.example.ai.agent.business.dataset.model;

import org.example.ai.agent.business.model.DatasetExecutionStatus;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 报告数据集安全执行结果。
 *
 * safeFacts 只能包含经过字段策略处理后的安全事实，字符串表示只展示通道名称。
 */
public record DatasetExecutionResult(
        String datasetCode,
        DatasetExecutionStatus status,
        boolean dataComplete,
        Map<String, Object> safeFacts,
        String workflowRunId,
        String resultArtifactId,
        String safeErrorCode,
        String safeMessage) {

    private static final Set<Class<?>> IMMUTABLE_NUMBER_TYPES = Set.of(
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            BigInteger.class,
            BigDecimal.class
    );

    public DatasetExecutionResult {
        safeFacts = freezeMap(safeFacts, new IdentityHashMap<>());
    }

    private static Map<String, Object> freezeMap(
            Map<?, ?> source,
            IdentityHashMap<Object, Boolean> recursionPath) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        enter(source, recursionPath);
        try {
            Map<String, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("safeFacts的键必须是字符串");
                }
                frozen.put(key, freezeValue(entry.getValue(), recursionPath));
            }
            return Collections.unmodifiableMap(frozen);
        } finally {
            recursionPath.remove(source);
        }
    }

    private static Object freezeValue(
            Object value,
            IdentityHashMap<Object, Boolean> recursionPath) {
        if (value == null || isImmutableScalar(value)) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            return freezeMap(map, recursionPath);
        }
        if (value instanceof Collection<?> collection) {
            enter(collection, recursionPath);
            try {
                List<Object> frozen = new ArrayList<>(collection.size());
                for (Object item : collection) {
                    frozen.add(freezeValue(item, recursionPath));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                recursionPath.remove(collection);
            }
        }
        if (value.getClass().isArray()) {
            enter(value, recursionPath);
            try {
                List<Object> frozen = new ArrayList<>(Array.getLength(value));
                for (int index = 0; index < Array.getLength(value); index++) {
                    frozen.add(freezeValue(Array.get(value, index), recursionPath));
                }
                return Collections.unmodifiableList(frozen);
            } finally {
                recursionPath.remove(value);
            }
        }
        throw new IllegalArgumentException(
                "safeFacts包含不支持的值类型：" + value.getClass().getName()
        );
    }

    private static void enter(
            Object value,
            IdentityHashMap<Object, Boolean> recursionPath) {
        if (recursionPath.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("safeFacts不能包含循环引用");
        }
    }

    private static boolean isImmutableScalar(Object value) {
        Package valuePackage = value.getClass().getPackage();
        String packageName = valuePackage == null ? "" : valuePackage.getName();
        return value instanceof String
                || IMMUTABLE_NUMBER_TYPES.contains(value.getClass())
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || "java.time".equals(packageName)
                || value instanceof UUID;
    }

    /**
     * 不输出任何事实值，避免日志或异常消息意外携带业务数据。
     */
    @Override
    public String toString() {
        return "DatasetExecutionResult["
                + "datasetCode=" + datasetCode
                + ", status=" + status
                + ", dataComplete=" + dataComplete
                + ", safeFactChannels=" + safeFacts.keySet()
                + ", workflowRunId=" + workflowRunId
                + ", resultArtifactId=" + resultArtifactId
                + ", safeErrorCode=" + safeErrorCode
                + ", safeMessage=" + safeMessage
                + ']';
    }
}
