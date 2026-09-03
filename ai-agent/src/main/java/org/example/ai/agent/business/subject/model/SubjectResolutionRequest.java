package org.example.ai.agent.business.subject.model;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.SubjectRequestLimits;

import java.util.Map;

/**
 * 主体定位请求。认证信息只在本次目录查询内存中传递。
 */
public record SubjectResolutionRequest(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        BusinessSubjectType subjectType,
        String selectionToken,
        String projectCode,
        String searchName,
        String projectManager,
        Integer projectYear,
        boolean myProjects,
        String employeeNo,
        int pageNumber,
        int pageSize) {

    @SuppressWarnings("unchecked")
    public SubjectResolutionRequest {
        secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                secureContext == null ? Map.of() : secureContext
        );
        SubjectRequestLimits.validateContext(secureContext);
    }

    /**
     * 只展示非敏感结构摘要。
     */
    @Override
    public String toString() {
        return "SubjectResolutionRequest["
                + "authorizationPresent=" + (authorization != null && !authorization.isBlank())
                + ", secureContextPresent=" + !secureContext.isEmpty()
                + ", subjectType=" + subjectType
                + ", selectionTokenPresent=" + (selectionToken != null)
                + ", projectCodePresent=" + (projectCode != null)
                + ", searchNamePresent=" + (searchName != null)
                + ", projectManagerPresent=" + (projectManager != null)
                + ", projectYear=" + projectYear
                + ", myProjects=" + myProjects
                + ", employeeNoPresent=" + (employeeNo != null)
                + ", pageNumber=" + pageNumber
                + ", pageSize=" + pageSize
                + ']';
    }
}
