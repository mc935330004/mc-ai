package org.example.ai.agent.graph.runtime;

import lombok.Getter;

/**
 * GraphSpec安全运行异常。
 */
@Getter
public class GraphExecutionException
        extends RuntimeException {

    private final String errorCode;

    public GraphExecutionException(
            String errorCode,
            String message) {

        super(message);
        this.errorCode = errorCode;
    }

    public GraphExecutionException(
            String errorCode,
            String message,
            Throwable cause) {

        super(message, cause);
        this.errorCode = errorCode;
    }
}