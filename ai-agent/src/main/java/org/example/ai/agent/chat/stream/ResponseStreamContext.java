package org.example.ai.agent.chat.stream;

/**
 * 一次AI回答流的固定上下文。
 *
 * 同一次回答中的所有SSE事件，
 * 必须使用相同的回答、运行和会话标识。
 */
public record ResponseStreamContext(
        String responseId,
        String runId,
        String conversationId) {

    public ResponseStreamContext {
        responseId = requireText(
                responseId,
                "回答responseId不能为空"
        );

        runId = requireText(
                runId,
                "回答runId不能为空"
        );

        conversationId = requireText(
                conversationId,
                "回答conversationId不能为空"
        );
    }

    private static String requireText(
            String value,
            String message) {

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }
}