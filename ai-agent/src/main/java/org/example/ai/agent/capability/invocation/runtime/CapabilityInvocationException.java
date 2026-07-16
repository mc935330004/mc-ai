package org.example.ai.agent.capability.invocation.runtime;

import lombok.Getter;

/**
 * 能力请求构建或调用异常。
 *
 * message 中禁止放入：
 * Authorization、Cookie、Token、请求敏感值。
 */
@Getter
public class CapabilityInvocationException extends RuntimeException {

    private final String errorCode;

    public CapabilityInvocationException(
            String errorCode,
            String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public CapabilityInvocationException(
            String errorCode,
            String message,
            Throwable cause) {

        super(message, cause);
        this.errorCode = errorCode;
    }
}