package org.example.ai.agent.capability.invocation.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;

/**
 * 受限表达式解析器。
 *
 * 只允许读取对象属性，不允许：
 * 1. 调用 Java 方法；
 * 2. 执行 SpEL；
 * 3. 执行脚本；
 * 4. 动态访问 Spring Bean；
 * 5. 修改上下文。
 */
@Component
@RequiredArgsConstructor
public class RestrictedExpressionResolver {

    private final ObjectMapper objectMapper;

    public Object resolve(
            String expression,
            CapabilityInvocationContext context) {

        if (!StringUtils.hasText(expression)) {
            throw invalid("变量表达式不能为空");
        }

        if (context == null) {
            return null;
        }

        String normalized = normalizeLegacyExpression(
                expression.trim()
        );

        RootResolution root = resolveRoot(
                normalized,
                context
        );

        Object current = root.value();

        if (!StringUtils.hasText(root.remainingPath())) {
            return unwrap(current);
        }

        String[] parts =
                root.remainingPath().split("\\.");

        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                throw invalid(
                        "变量表达式包含空路径：" +
                                expression
                );
            }

            current = readChild(current, part);

            if (current == null) {
                return null;
            }
        }

        return unwrap(current);
    }

    /**
     * 兼容旧版 PlanStep.inputRef：
     *
     * $.project.id
     * 等价于：
     * $vars.project.id
     */
    private String normalizeLegacyExpression(
            String expression) {

        if (expression.startsWith("$.")) {
            return "$vars." + expression.substring(2);
        }

        return expression;
    }

    private RootResolution resolveRoot(
            String expression,
            CapabilityInvocationContext context) {

        if (isRoot(expression, "$input")) {
            return new RootResolution(
                    context.getInput(),
                    remaining(expression, "$input")
            );
        }

        if (isRoot(expression, "$vars")) {
            return new RootResolution(
                    context.getVariables(),
                    remaining(expression, "$vars")
            );
        }

        if (isRoot(expression, "$item")) {
            return new RootResolution(
                    context.getItem(),
                    remaining(expression, "$item")
            );
        }

        if (isRoot(expression, "$secure")) {
            return new RootResolution(
                    context.getSecure(),
                    remaining(expression, "$secure")
            );
        }

        throw invalid(
                "不支持的变量表达式根路径：" +
                        expression
        );
    }

    private boolean isRoot(
            String expression,
            String root) {

        return expression.equals(root)
                || expression.startsWith(root + ".");
    }

    private String remaining(
            String expression,
            String root) {

        if (expression.equals(root)) {
            return "";
        }

        return expression.substring(root.length() + 1);
    }

    private Object readChild(
            Object current,
            String property) {

        if (current == null) {
            return null;
        }

        if (current instanceof Map<?, ?> map) {
            return map.get(property);
        }

        if (current instanceof JsonNode jsonNode) {
            if (jsonNode.isObject()) {
                return jsonNode.get(property);
            }

            if (jsonNode.isArray()) {
                Integer index = parseIndex(property);
                return index == null
                        || index < 0
                        || index >= jsonNode.size()
                        ? null
                        : jsonNode.get(index);
            }

            return null;
        }

        if (current instanceof List<?> list) {
            Integer index = parseIndex(property);

            return index == null
                    || index < 0
                    || index >= list.size()
                    ? null
                    : list.get(index);
        }

        if (current.getClass().isArray()) {
            Integer index = parseIndex(property);

            return index == null
                    || index < 0
                    || index >= Array.getLength(current)
                    ? null
                    : Array.get(current, index);
        }

        /*
         * 对普通 DTO/VO 使用 Jackson 只读转换，
         * 不使用反射调用 Getter 或其他任意方法。
         */
        JsonNode converted =
                objectMapper.valueToTree(current);

        return converted.isObject()
                ? converted.get(property)
                : null;
    }

    private Integer parseIndex(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Object unwrap(Object value) {
        if (!(value instanceof JsonNode node)) {
            return value;
        }

        if (node.isNull() || node.isMissingNode()) {
            return null;
        }

        return objectMapper.convertValue(
                node,
                Object.class
        );
    }

    private CapabilityInvocationException invalid(
            String message) {

        return new CapabilityInvocationException(
                "CAPABILITY_EXPRESSION_INVALID",
                message
        );
    }

    private record RootResolution(
            Object value,
            String remainingPath) {
    }
}