package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/**
 * 主体定位入口的容量边界，防止超长提示或深层安全上下文放大解析与日志风险。
 */
public final class SubjectRequestLimits {

    private static final int MAX_CONTEXT_BYTES = 16 * 1024;
    private static final int MAX_CONTEXT_DEPTH = 8;
    private static final int MAX_CONTEXT_ENTRIES = 64;

    private SubjectRequestLimits() {
    }

    public static void validateContext(Map<String, Object> context) {
        String canonical = ReportDatasetValidator.canonicalSafeValue(context);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) {
            throw new IllegalArgumentException("安全上下文超过容量上限");
        }
        Counter counter = new Counter();
        inspect(context, 0, counter);
    }

    public static void requireText(String value, String field, int maxBytes) {
        if (value == null
                || value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }

    public static void optionalText(String value, String field, int maxBytes) {
        if (value != null
                && (!value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes)) {
            if (value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                throw new IllegalArgumentException(field + "超过容量上限");
            }
        }
    }

    private static void inspect(Object value, int depth, Counter counter) {
        if (depth > MAX_CONTEXT_DEPTH) {
            throw new IllegalArgumentException("安全上下文嵌套过深");
        }
        if (value instanceof Map<?, ?> map) {
            counter.add(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || key.isBlank()
                        || key.getBytes(StandardCharsets.UTF_8).length > 128) {
                    throw new IllegalArgumentException("安全上下文键不合法");
                }
                inspect(entry.getValue(), depth + 1, counter);
            }
        } else if (value instanceof Collection<?> collection) {
            counter.add(collection.size());
            for (Object item : collection) {
                inspect(item, depth + 1, counter);
            }
        }
    }

    private static final class Counter {
        private int value;

        private void add(int count) {
            value = Math.addExact(value, count);
            if (value > MAX_CONTEXT_ENTRIES) {
                throw new IllegalArgumentException("安全上下文条目过多");
            }
        }
    }
}
