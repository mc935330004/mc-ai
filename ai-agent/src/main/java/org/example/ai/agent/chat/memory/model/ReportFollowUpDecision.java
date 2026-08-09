package org.example.ai.agent.chat.memory.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户对报告追问的确定性处理结果。
 */
public record ReportFollowUpDecision(
        Status status,
        String message,
        String targetType,
        String targetCode,
        Map<String, Object> input) {

    public ReportFollowUpDecision {

        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(input)
                );
    }

    /**
     * 追问处理状态。
     */
    public enum Status {
        NONE,
        CANCELLED,
        CLARIFY,
        READY
    }

    public static ReportFollowUpDecision none() {
        return new ReportFollowUpDecision(
                Status.NONE,
                null,
                null,
                null,
                Map.of()
        );
    }

    public static ReportFollowUpDecision cancelled(
            String message) {

        return new ReportFollowUpDecision(
                Status.CANCELLED,
                message,
                null,
                null,
                Map.of()
        );
    }

    public static ReportFollowUpDecision clarify(
            String message) {

        return new ReportFollowUpDecision(
                Status.CLARIFY,
                message,
                null,
                null,
                Map.of()
        );
    }

    public static ReportFollowUpDecision ready(
            String targetType,
            String targetCode,
            Map<String, Object> input) {

        return new ReportFollowUpDecision(
                Status.READY,
                null,
                targetType,
                targetCode,
                input
        );
    }
}