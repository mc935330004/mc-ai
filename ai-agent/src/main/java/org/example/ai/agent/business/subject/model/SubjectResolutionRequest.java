package org.example.ai.agent.business.subject.model;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.SubjectRequestLimits;
import org.example.ai.agent.common.model.ProjectListScope;

import java.util.Map;

/**
 * 主体定位请求。
 * 认证信息只在本次目录查询内存中传递。
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
        ProjectListScope projectListScope,
        String employeeNo,
        int pageNumber,
        int pageSize) {

    @SuppressWarnings("unchecked")
    public SubjectResolutionRequest {
        secureContext = (Map<String, Object>)
                ReportDatasetValidator.freezeSafeValue(
                        secureContext == null
                                ? Map.of()
                                : secureContext
                );

        SubjectRequestLimits.validateContext(secureContext);
    }

    /**
     * 兼容当前调用代码。
     * 后续业务代码应直接传入ProjectListScope。
     */
    public SubjectResolutionRequest(
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
            boolean projectListScope,
            String employeeNo,
            int pageNumber,
            int pageSize) {
        this(
                agentRunId,
                userId,
                sessionId,
                authorization,
                secureContext,
                subjectType,
                selectionToken,
                projectCode,
                searchName,
                projectManager,
                projectYear,
                projectListScope
                        ? ProjectListScope.MY_PROJECTS
                        : null,
                employeeNo,
                pageNumber,
                pageSize
        );
    }

    /**
     * 兼容现有主体定位逻辑。
     */
    public boolean myProjects() {
        return projectListScope == ProjectListScope.MY_PROJECTS;
    }

    /**
     * 判断是否为明确的可查看项目查询。
     */
    public boolean viewableProjects() {
        return projectListScope == ProjectListScope.VIEWABLE_PROJECTS;
    }

    /**
     * 只展示非敏感结构摘要。
     */
    @Override
    public String toString() {
        return "SubjectResolutionRequest["
                + "authorizationPresent="
                + (authorization != null
                && !authorization.isBlank())
                + ", secureContextPresent="
                + !secureContext.isEmpty()
                + ", subjectType=" + subjectType
                + ", selectionTokenPresent="
                + (selectionToken != null)
                + ", projectCodePresent="
                + (projectCode != null)
                + ", searchNamePresent="
                + (searchName != null)
                + ", projectManagerPresent="
                + (projectManager != null)
                + ", projectYear=" + projectYear
                + ", projectListScope="
                + projectListScope
                + ", employeeNoPresent="
                + (employeeNo != null)
                + ", pageNumber=" + pageNumber
                + ", pageSize=" + pageSize
                + ']';
    }
}