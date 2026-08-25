package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.common.enums.protocol.ResponseStreamEventType;

import java.time.Instant;
import java.util.Objects;

/**
 * 新版AI回答统一SSE事件。
 *
 * 所有事件共用同一个信封结构，
 * payload根据eventType使用对应的数据类型。
 */
public record ResponseStreamEvent<T>(
        int schemaVersion,
        String eventId,
        String responseId,
        String runId,
        String conversationId,
        long sequence,
        ResponseStreamEventType eventType,
        Instant emittedAt,
        T payload) {

    /**
     * 当前SSE事件协议版本。
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ResponseStreamEvent {
        schemaVersion = schemaVersion <= 0
                ? CURRENT_SCHEMA_VERSION
                : schemaVersion;

        eventId = StreamSupport.requireText(
                eventId,
                "SSE事件eventId不能为空"
        );

        responseId = StreamSupport.requireText(
                responseId,
                "SSE事件responseId不能为空"
        );

        runId = StreamSupport.requireText(
                runId,
                "SSE事件runId不能为空"
        );

        conversationId = StreamSupport.requireText(
                conversationId,
                "SSE事件conversationId不能为空"
        );

        sequence = Math.max(sequence, 0);

        eventType = Objects.requireNonNull(
                eventType,
                "SSE事件eventType不能为空"
        );

        emittedAt = emittedAt == null
                ? Instant.now()
                : emittedAt;

        payload = Objects.requireNonNull(
                payload,
                "SSE事件payload不能为空"
        );
    }
}

/**
 * SSE协议字段的公共校验方法。
 *
 * 只处理协议参数，不承载事件发送逻辑。
 */
final class StreamSupport {

    private StreamSupport() {
    }

    static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    static long normalizeSequence(long sequence) {
        return Math.max(sequence, 0);
    }
}