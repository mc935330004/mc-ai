package org.example.ai.agent.business.subject.model;

import java.util.List;
import java.util.Objects;

/**
 * 可直接用于对话编排的主体定位结果。
 */
public record SubjectResolutionResult(
        SubjectResolutionState state,
        SubjectCandidate resolvedSubject,
        List<SubjectCandidate> candidates,
        int pageNumber,
        int pageSize,
        long totalCount,
        boolean hasNext,
        String safeMessage) {

    public SubjectResolutionResult {
        state = Objects.requireNonNull(state, "state不能为空");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (state == SubjectResolutionState.RESOLVED && resolvedSubject == null) {
            throw new IllegalArgumentException("RESOLVED状态必须携带唯一主体");
        }
        if (state != SubjectResolutionState.RESOLVED && resolvedSubject != null) {
            throw new IllegalArgumentException("非RESOLVED状态不能携带已选主体");
        }
        if (state != SubjectResolutionState.CANDIDATES && !candidates.isEmpty()) {
            throw new IllegalArgumentException("只有CANDIDATES状态可以携带候选");
        }
        if (state == SubjectResolutionState.CANDIDATES && candidates.isEmpty()) {
            throw new IllegalArgumentException("CANDIDATES状态必须携带候选");
        }
        if (pageNumber < 1
                || pageSize < 1
                || totalCount < 0
                || candidates.size() > pageSize
                || totalCount < candidates.size()) {
            throw new IllegalArgumentException("主体定位分页信息不合法");
        }
        if ((state == SubjectResolutionState.EMPTY
                || state == SubjectResolutionState.DENIED)
                && (totalCount != 0 || hasNext)) {
            throw new IllegalArgumentException("终止状态不能携带主体数量");
        }
        if (safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("safeMessage不能为空");
        }
    }
}
