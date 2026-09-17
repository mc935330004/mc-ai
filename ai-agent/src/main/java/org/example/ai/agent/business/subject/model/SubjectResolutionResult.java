package org.example.ai.agent.business.subject.model;

import java.util.List;
import java.util.Objects;

/**
 * 可直接用于业务助手编排的主体定位结果。
 */
public record SubjectResolutionResult(
        SubjectResolutionState state,
        SubjectCandidate resolvedSubject,
        List<SubjectCandidate> candidates,
        int pageNumber,
        int pageSize,
        long totalCount,
        boolean totalKnown,
        boolean hasNext,
        String safeMessage) {

    public SubjectResolutionResult {
        state = Objects.requireNonNull(
                state,
                "state不能为空"
        );

        candidates = candidates == null
                ? List.of()
                : List.copyOf(candidates);

        if (state == SubjectResolutionState.RESOLVED
                && resolvedSubject == null) {
            throw new IllegalArgumentException(
                    "RESOLVED状态必须携带唯一主体"
            );
        }

        if (state != SubjectResolutionState.RESOLVED
                && resolvedSubject != null) {
            throw new IllegalArgumentException(
                    "非RESOLVED状态不能携带已选主体"
            );
        }

        if (state != SubjectResolutionState.CANDIDATES
                && !candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "只有CANDIDATES状态可以携带候选"
            );
        }

        if (state == SubjectResolutionState.CANDIDATES
                && candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "CANDIDATES状态必须携带候选"
            );
        }

        if (pageNumber < 1
                || pageSize < 1
                || totalCount < 0
                || candidates.size() > pageSize) {
            throw new IllegalArgumentException(
                    "主体定位分页信息不合法"
            );
        }

        if (!totalKnown && totalCount != 0) {
            throw new IllegalArgumentException(
                    "未知总数时不能伪造totalCount"
            );
        }

        if (totalKnown
                && totalCount < candidates.size()) {
            throw new IllegalArgumentException(
                    "主体定位总数不能小于当前页数量"
            );
        }

        if (state == SubjectResolutionState.RESOLVED
                && (!totalKnown
                || totalCount != 1
                || hasNext)) {
            throw new IllegalArgumentException(
                    "已定位主体必须是唯一结果"
            );
        }

        if ((state == SubjectResolutionState.EMPTY
                || state == SubjectResolutionState.DENIED)
                && (totalCount != 0
                || !totalKnown
                || hasNext)) {
            throw new IllegalArgumentException(
                    "终止状态不能携带主体数量"
            );
        }

        if (safeMessage == null
                || safeMessage.isBlank()) {
            throw new IllegalArgumentException(
                    "safeMessage不能为空"
            );
        }
    }

    /**
     * 兼容原有已知总数构造方式。
     */
    public SubjectResolutionResult(
            SubjectResolutionState state,
            SubjectCandidate resolvedSubject,
            List<SubjectCandidate> candidates,
            int pageNumber,
            int pageSize,
            long totalCount,
            boolean hasNext,
            String safeMessage) {
        this(
                state,
                resolvedSubject,
                candidates,
                pageNumber,
                pageSize,
                totalCount,
                true,
                hasNext,
                safeMessage
        );
    }
}