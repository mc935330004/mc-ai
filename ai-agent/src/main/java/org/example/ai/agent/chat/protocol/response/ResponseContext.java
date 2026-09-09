package org.example.ai.agent.chat.protocol.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CHAT回答的通用业务上下文。
 *
 * 只描述当前回答对象和时间范围，
 * 不携带业务专属字段或原始查询数据。
 */
public record ResponseContext(
        String subjectType,
        String subjectId,
        String subjectLabel,
        String scopeLabel,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDateTime snapshotAt) {

    public ResponseContext {
        subjectType = normalize(subjectType);
        subjectId = normalize(subjectId);
        subjectLabel = normalize(subjectLabel);
        scopeLabel = normalize(scopeLabel);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
