package org.example.ai.agent.pending.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 待确认操作预览脱敏工具。
 *
 * 数据库继续保存真实执行参数，
 * 聊天记录和接口只返回脱敏后的预览参数。
 */
public final class PendingActionPreviewMasker {

    private static final String MASKED_VALUE = "******";

    private PendingActionPreviewMasker() {
    }

    /**
     * 递归脱敏操作预览参数。
     */
    public static Map<String, Object> mask(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (input == null || input.isEmpty()) {
            return result;
        }

        input.forEach((key, value) -> result.put(
                key,
                isSensitiveName(key) ? MASKED_VALUE : maskValue(value)
        ));
        return result;
    }

    /**
     * 递归处理嵌套对象和数组。
     */
    private static Object maskValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String fieldName = String.valueOf(key);
                result.put(fieldName, isSensitiveName(fieldName)
                        ? MASKED_VALUE
                        : maskValue(item));
            });
            return result;
        }

        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(item -> result.add(maskValue(item)));
            return result;
        }
        return value;
    }

    /**
     * 判断字段是否可能包含认证信息或密钥。
     */
    private static boolean isSensitiveName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }

        String normalized = fieldName.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");

        return normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("credential")
                || normalized.contains("apikey")
                || normalized.contains("accesskey")
                || normalized.contains("privatekey");
    }
}