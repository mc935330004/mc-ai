package org.example.ai.agent.graph.runtime;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationContext;
import org.example.ai.agent.capability.invocation.runtime.RestrictedExpressionResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GraphSpec运行表达式解析器。
 *
 * 复用现有受限表达式解析能力，
 * 不引入SpEL、脚本或动态方法调用。
 */
@Component
@RequiredArgsConstructor
public class GraphRuntimeExpressionResolver {

    /**
     * 兼容旧工作流把原始响应的data层级重复写入workflowData的配置。
     */
    private static final Pattern LEGACY_WORKFLOW_DATA_PATH = Pattern.compile(
            "^\\$vars\\.([A-Za-z0-9_-]+)\\.workflowData\\.data\\.(.+)$"
    );

    private final RestrictedExpressionResolver
            restrictedExpressionResolver;

    public Object resolve(
            String expression,
            GraphExecutionContext context) {

        if (expression != null
                && (expression.equals("$secure")
                || expression.startsWith("$secure."))) {

            throw new GraphExecutionException(
                    "GRAPH_SECURE_EXPRESSION_FORBIDDEN",
                    "GraphSpec不能直接读取安全上下文"
            );
        }

        CapabilityInvocationContext invocationContext =
                CapabilityInvocationContext.builder()
                        .input(context.getInput())
                        .variables(
                                context.snapshotVariables()
                        )
                        .item(
                                context.getCurrentItem()
                        )
                        /*
                         * Graph表达式禁止访问secure。
                         * 只有能力请求绑定可以读取安全上下文。
                         */
                        .secure(Map.of())
                        .build();

        Object value = restrictedExpressionResolver.resolve(
                expression,
                invocationContext
        );

        if (value != null) {
            return value;
        }

        return resolveLegacyWorkflowDataPath(
                expression,
                context,
                invocationContext
        );
    }

    /**
     * 旧配置路径如果多写了一层data，且当前workflowData已经是投影后的扁平结构，
     * 自动尝试去掉重复层级，避免已发布工作流必须全部手工重配。
     */
    private Object resolveLegacyWorkflowDataPath(
            String expression,
            GraphExecutionContext context,
            CapabilityInvocationContext invocationContext) {

        Matcher matcher = LEGACY_WORKFLOW_DATA_PATH.matcher(
                expression == null ? "" : expression
        );
        if (!matcher.matches()) {
            return null;
        }

        Object envelope = context.snapshotVariables().get(
                matcher.group(1)
        );
        if (!(envelope instanceof Map<?, ?> envelopeMap)) {
            return null;
        }

        Object workflowData = envelopeMap.get("workflowData");
        if (!(workflowData instanceof Map<?, ?> workflowDataMap)
                || workflowDataMap.containsKey("data")) {
            return null;
        }

        String compatibleExpression = "$vars."
                + matcher.group(1)
                + ".workflowData."
                + matcher.group(2);

        return restrictedExpressionResolver.resolve(
                compatibleExpression,
                invocationContext
        );
    }

    public Map<String, Object> resolveMap(Map<String, Object> source,GraphExecutionContext context) {

        Map<String, Object> result = new LinkedHashMap<>();

        if (source == null) {
            return result;
        }

        source.forEach((key, value) ->
                result.put(key, resolveConfiguredValue( value,context ))
        );

        return result;
    }

    private Object resolveConfiguredValue(
            Object value,
            GraphExecutionContext context) {

        if (value instanceof String text
                && text.startsWith("$")) {

            return resolve(text, context);
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result =
                    new LinkedHashMap<>();

            map.forEach((key, child) ->
                    result.put(
                            String.valueOf(key),
                            resolveConfiguredValue(
                                    child,
                                    context
                            )
                    )
            );

            return result;
        }

        if (value instanceof Collection<?> collection) {
            List<Object> result =
                    new ArrayList<>();

            collection.forEach(child ->
                    result.add(
                            resolveConfiguredValue(
                                    child,
                                    context
                            )
                    )
            );

            return result;
        }

        return value;
    }
}
