package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.common.enums.protocol.ResponseStreamEventType;

import java.time.Instant;
import java.util.Objects;

/**
 * AI聊天和报告共用的SSE事件。
 *
 * sequence负责整次回答的事件排序。
 * revision负责同一个Block的内容更新顺序。
 */
public record ResponseStreamEvent<T>(
        int schemaVersion,
        String eventId,
        String responseId,
        String runId,
        String conversationId,
        String blockId,
        long sequence,
        long revision,
        ResponseStreamEventType eventType,
        Instant emittedAt,
        T payload) {

    /**
     * 当前统一协议版本。
     *
     * 项目尚未上线，不再保留v1、v2、v3并行结构。
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

        /*
         * RESPONSE_START、RESPONSE_DONE等响应级事件没有blockId，
         * 统一保存为空字符串。
         */
        blockId = StreamSupport.normalizeText(blockId);
        sequence = StreamSupport.normalizeSequence(sequence);
        revision = StreamSupport.normalizeRevision(revision);

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
 * SSE协议字段校验。
 */
final class StreamSupport {

    private StreamSupport() {
    }

    static String requireText(
            String value,
            String message) {

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

    static long normalizeRevision(long revision) {
        return Math.max(revision, 0);
    }
}