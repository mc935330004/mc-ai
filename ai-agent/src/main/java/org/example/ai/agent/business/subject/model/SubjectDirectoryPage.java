package org.example.ai.agent.business.subject.model;

import java.util.List;

/**
 * 来源系统授权目录的一页安全候选。
 *
 * totalKnown=false表示PM没有返回可靠总数，
 * 此时只能使用hasNext判断是否还有下一页。
 */
public record SubjectDirectoryPage(
        boolean accessible,
        List<AuthorizedSubjectCandidate> candidates,
        int pageNumber,
        int pageSize,
        long totalCount,
        boolean totalKnown,
        boolean hasNext) {

    public SubjectDirectoryPage {
        candidates = candidates == null
                ? List.of()
                : List.copyOf(candidates);

        if (pageNumber < 1
                || pageSize < 1
                || totalCount < 0
                || candidates.size() > pageSize) {
            throw new IllegalArgumentException(
                    "主体目录分页信息不合法"
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
                    "主体目录总数不能小于当前页数量"
            );
        }

        if (!accessible
                && (!candidates.isEmpty()
                || totalCount != 0
                || !totalKnown
                || hasNext)) {
            throw new IllegalArgumentException(
                    "不可访问的主体目录不能携带候选信息"
            );
        }

        if (candidates.isEmpty() && hasNext) {
            throw new IllegalArgumentException(
                    "空页不能标记存在下一页"
            );
        }
    }

    /**
     * 兼容原有已知总数构造方式。
     */
    public SubjectDirectoryPage(
            boolean accessible,
            List<AuthorizedSubjectCandidate> candidates,
            int pageNumber,
            int pageSize,
            long totalCount,
            boolean hasNext) {
        this(
                accessible,
                candidates,
                pageNumber,
                pageSize,
                totalCount,
                true,
                hasNext
        );
    }

    public static SubjectDirectoryPage denied() {
        return new SubjectDirectoryPage(
                false,
                List.of(),
                1,
                1,
                0,
                true,
                false
        );
    }

    public static SubjectDirectoryPage empty(
            int pageNumber,
            int pageSize) {
        return new SubjectDirectoryPage(
                true,
                List.of(),
                pageNumber,
                pageSize,
                0,
                true,
                false
        );
    }

    /**
     * PM只返回hasNext，没有可靠总数时使用。
     */
    public static SubjectDirectoryPage unknownTotal(
            List<AuthorizedSubjectCandidate> candidates,
            int pageNumber,
            int pageSize,
            boolean hasNext) {
        return new SubjectDirectoryPage(
                true,
                candidates,
                pageNumber,
                pageSize,
                0,
                false,
                hasNext
        );
    }
}