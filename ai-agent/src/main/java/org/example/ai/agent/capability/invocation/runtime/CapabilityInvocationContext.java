package org.example.ai.agent.capability.invocation.runtime;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 单次能力调用的变量根对象。
 *
 * 支持：
 * $input
 * $vars
 * $item
 * $secure
 *
 * 不使用 @Data，避免默认 toString 输出安全上下文。
 */
@Getter
@Builder
public class CapabilityInvocationContext {

    private Map<String, Object> input;

    private Map<String, Object> variables;

    private Object item;

    private Map<String, Object> secure;
}