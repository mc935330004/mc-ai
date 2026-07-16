package org.example.ai.agent.capability.invocation.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 受限JSON路径读取器。
 *
 * 不依赖第三方JsonPath表达式引擎，
 * 避免脚本表达式、递归查询和复杂过滤带来的安全风险。
 */
@Component
public class SimpleJsonPathReader {

    public ReadResult read( JsonNode root,String path) {

        if (root == null || !StringUtils.hasText(path)) {

            return ReadResult.missing();
        }

        if ("$".equals(path)) {
            return ReadResult.found(root);
        }

        if (!path.startsWith("$.")) {
            return ReadResult.missing();
        }

        String[] parts = path.substring(2).split("\\.");

        JsonNode current = root;

        for (String part : parts) {
            if (current == null || current.isNull() || current.isMissingNode()) {

                return ReadResult.missing();
            }

            if (current.isObject()) {
                if (!current.has(part)) {
                    return ReadResult.missing();
                }

                current = current.get(part);
                continue;
            }

            if (current.isArray() && isArrayIndex(part)) {

                int index;

                try {
                    index = Integer.parseInt(part);
                } catch (NumberFormatException exception) {
                    return ReadResult.missing();
                }

                if (index < 0 || index >= current.size()) {
                    return ReadResult.missing();
                }

                current = current.get(index);
                continue;
            }

            return ReadResult.missing();
        }

        return ReadResult.found(current);
    }

    private boolean isArrayIndex(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        for (int index = 0; index < value.length();index++) {
            if (!Character.isDigit( value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 必须同时保留found和值。
     *
     * 因为“路径不存在”和“路径存在但值为null”
     * 在响应解释时是两个不同概念。
     */
    public record ReadResult(  boolean found,JsonNode value) {

        public static ReadResult found( JsonNode value) {
            return new ReadResult(true,value );
        }

        public static ReadResult missing() {
            return new ReadResult(false,null);
        }
    }
}