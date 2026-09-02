package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;

import java.util.Map;

/**
 * 已经由后端确定查询方式的来源系统目录请求。
 */
public record SubjectDirectoryQuery(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        BusinessSubjectType subjectType,
        SubjectSearchMode searchMode,
        String selectedSubjectId,
        String projectCode,
        String searchName,
        String projectManager,
        Integer projectYear,
        String employeeNo,
        int pageNumber,
        int pageSize) {

    @SuppressWarnings("unchecked")
    public SubjectDirectoryQuery {
        secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                secureContext == null ? Map.of() : secureContext
        );
    }

    /**
     * 不输出任何主体搜索值、认证或安全上下文。
     */
    @Override
    public String toString() {
        return "SubjectDirectoryQuery["
                + "agentRunId=" + agentRunId
                + ", userId=" + userId
                + ", sessionId=" + sessionId
                + ", authorizationPresent=" + (authorization != null && !authorization.isBlank())
                + ", secureContextPresent=" + !secureContext.isEmpty()
                + ", subjectType=" + subjectType
                + ", searchMode=" + searchMode
                + ", pageNumber=" + pageNumber
                + ", pageSize=" + pageSize
                + ']';
    }
}
