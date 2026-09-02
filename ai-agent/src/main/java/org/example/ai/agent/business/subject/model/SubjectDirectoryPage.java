package org.example.ai.agent.business.subject.model;

import java.util.List;

/**
 * 来源系统授权目录的一页安全候选。
 */
public record SubjectDirectoryPage(
        boolean accessible,
        List<SubjectCandidate> candidates,
        int pageNumber,
        int pageSize,
        long totalCount,
        boolean hasNext) {

    public SubjectDirectoryPage {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        if (pageNumber < 1 || pageSize < 1 || totalCount < 0) {
            throw new IllegalArgumentException("主体目录分页信息不合法");
        }
        if (!accessible && (!candidates.isEmpty() || totalCount != 0 || hasNext)) {
            throw new IllegalArgumentException("不可访问的主体目录不能携带候选信息");
        }
        if (accessible && totalCount < candidates.size()) {
            throw new IllegalArgumentException("主体目录总数不能小于当前页数量");
        }
    }

    public static SubjectDirectoryPage denied() {
        return new SubjectDirectoryPage(false, List.of(), 1, 1, 0, false);
    }

    public static SubjectDirectoryPage empty(int pageNumber, int pageSize) {
        return new SubjectDirectoryPage(true, List.of(), pageNumber, pageSize, 0, false);
    }
}
