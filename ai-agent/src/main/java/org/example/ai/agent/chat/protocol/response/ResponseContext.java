package org.example.ai.agent.chat.protocol.response;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
        LocalDateTime snapshotAt,
        @JsonIgnore String accessToken) {

    public ResponseContext {
        subjectType = normalize(subjectType);
        subjectId = normalize(subjectId);
        subjectLabel = normalize(subjectLabel);
        scopeLabel = normalize(scopeLabel);
        accessToken = normalize(accessToken);
    }

    /**
     * 普通回答继续使用原有构造方式。
     */
    public ResponseContext(
            String subjectType,
            String subjectId,
            String subjectLabel,
            String scopeLabel,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDateTime snapshotAt) {
        this(
                subjectType,
                subjectId,
                subjectLabel,
                scopeLabel,
                periodStart,
                periodEnd,
                snapshotAt,
                ""
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}