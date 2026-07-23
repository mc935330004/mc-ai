package org.example.ai.agent.graph.runtime.pagination;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分页输入参数读取和复制工具。
 *
 * 作用：
 * 1. 读取current、size等分页参数；
 * 2. 支持page.current等嵌套路径；
 * 3. 每次生成新的输入Map；
 * 4. 不修改原始PlanStep输入。
 */
public final class PaginationInputMapper {

    private PaginationInputMapper() {
    }

    /**
     * 按点分路径读取输入值。
     */
    public static Object read( Map<String, Object> source,String path) {

        if (source == null
                || !StringUtils.hasText(path)) {
            return null;
        }

        Object current = source;

        for (String part : splitPath(path)) {

            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }

            if (!map.containsKey(part)) {
                return null;
            }

            current = map.get(part);
        }

        return current;
    }

    /**
     * 复制输入Map，并在副本中写入指定路径。
     *
     * 原始输入不会被修改，避免并发分页时相互污染。
     */
    public static Map<String, Object> writeCopy(
            Map<String, Object> source,String path,Object value) {

        String[] parts =splitPath(path);

        Map<String, Object> sourceRoot = source == null
                        ? Map.of()
                        : source;

        Map<String, Object> copiedRoot =new LinkedHashMap<>(sourceRoot);

        Map<?, ?> sourceCursor =sourceRoot;

        Map<String, Object> targetCursor =copiedRoot;

        for (int index = 0;index < parts.length - 1; index++) {

            String part = parts[index];

            Object sourceChild =sourceCursor.get(part);

            if (!(sourceChild instanceof Map<?, ?> sourceChildMap)) {

                throw new IllegalArgumentException(
                        "分页输入路径不存在或不是对象："
                                + part
                );
            }

            Map<String, Object> copiedChild = new LinkedHashMap<>();

            sourceChildMap.forEach((childKey, childValue) -> {
                        if (childKey != null) {
                            copiedChild.put(String.valueOf(childKey),childValue);
                        }
                    }
            );

            targetCursor.put(part, copiedChild);
            sourceCursor =sourceChildMap;
            targetCursor =copiedChild;
        }

        targetCursor.put(parts[parts.length - 1],value);

        return copiedRoot;
    }

    private static String[] splitPath(
            String path) {

        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException(
                    "分页输入路径不能为空"
            );
        }

        String[] parts =path.trim().split("\\.");

        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                throw new IllegalArgumentException("分页输入路径格式不正确："+ path);
            }
        }

        return parts;
    }
}