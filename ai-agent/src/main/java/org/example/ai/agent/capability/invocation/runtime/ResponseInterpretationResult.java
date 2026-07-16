package org.example.ai.agent.capability.invocation.runtime;

/**
 * 业务响应解释结果。
 */
public record ResponseInterpretationResult(
        boolean success,
        String errorCode,
        String errorMessage,
        String businessCode,
        String businessMessage,
        Object data,
        boolean emptyData) {

    public static ResponseInterpretationResult success(
            String businessCode,
            String businessMessage,
            Object data,
            boolean emptyData) {

        return new ResponseInterpretationResult(
                true,
                null,
                null,
                businessCode,
                businessMessage,
                data,
                emptyData
        );
    }

    public static ResponseInterpretationResult failure(
            String errorCode,
            String errorMessage,
            String businessCode,
            String businessMessage) {

        return new ResponseInterpretationResult(
                false,
                errorCode,
                errorMessage,
                businessCode,
                businessMessage,
                null,
                false
        );
    }
}