package org.example.ai.agent.capability.invocation.runtime;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将现有 ToolExecutionContext 转换成请求绑定上下文。
 */
@Component
@RequiredArgsConstructor
public class CapabilityInvocationContextFactory {

    private final RestrictedExpressionResolver expressionResolver;

    public CapabilityInvocationContext create(
            ToolExecutionContext toolContext,
            PlanStep step) {

        Map<String, Object> input =
                new LinkedHashMap<>();

        if (step != null
                && !CollectionUtils.isEmpty(step.getInput())) {
            input.putAll(step.getInput());
        }

        Map<String, Object> variables =
                toolContext == null
                        || toolContext.getVariables() == null
                        ? Map.of()
                        : toolContext.getVariables();

        Object item = toolContext == null
                ? null
                : toolContext.getCurrentItem();

        Map<String, Object> secure =
                buildSecureContext(toolContext);

        CapabilityInvocationContext initialContext =
                CapabilityInvocationContext.builder()
                        .input(input)
                        .variables(variables)
                        .item(item)
                        .secure(secure)
                        .build();

        /*
         * 兼容现有 PlanStep.inputRef。
         *
         * 后面的 GraphSpec 能直接通过 $vars 引用，
         * 但旧 Planner 仍可能把变量引用放入 inputRef。
         */
        if (step != null
                && !CollectionUtils.isEmpty(step.getInputRef())) {

            step.getInputRef().forEach(
                    (parameterName, expression) -> {

                        Object value =
                                expressionResolver.resolve(
                                        expression,
                                        initialContext
                                );

                        input.put(parameterName, value);
                    }
            );
        }

        return CapabilityInvocationContext.builder()
                .input(readOnlyCopy(input))
                .variables(readOnlyCopy(variables))
                .item(item)
                .secure(readOnlyCopy(secure))
                .build();
    }

    private Map<String, Object> buildSecureContext(
            ToolExecutionContext context) {

        Map<String, Object> secure =
                new LinkedHashMap<>();

        if (context == null) {
            return secure;
        }

        /*
         * 扩展安全属性先写入，
         * 保留字段后写入，防止 attributes 覆盖登录身份。
         */
        if (context.getSecureContext() != null) {
            secure.putAll(context.getSecureContext());
        }

        if (StringUtils.hasText(context.getUserId())) {
            secure.put("userId", context.getUserId());
        }

        if (StringUtils.hasText(
                context.getAuthorization()
        )) {
            secure.put(
                    "authorization",
                    context.getAuthorization()
            );
        }

        return secure;
    }

    private Map<String, Object> readOnlyCopy(
            Map<String, Object> source) {

        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        /*
         * 不使用 Map.copyOf，因为业务输入可能包含 null。
         */
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(source)
        );
    }
}