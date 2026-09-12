package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;

import java.util.Map;

/**
 * 已复权部门内部标识对应的授权成员分页请求。
 */
public record DepartmentMemberDirectoryQuery(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        String departmentSubjectId,
        int pageNumber,
        int pageSize) {

    @SuppressWarnings("unchecked")
    public DepartmentMemberDirectoryQuery {
        secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                secureContext == null ? Map.of() : secureContext
        );
        SubjectRequestLimits.validateContext(secureContext);
    }

    /**
     * 不输出部门标识、登录身份、认证或安全上下文。
     */
    @Override
    public String toString() {
        return "DepartmentMemberDirectoryQuery["
                + "authorizationPresent=" + (authorization != null && !authorization.isBlank())
                + ", secureContextPresent=" + !secureContext.isEmpty()
                + ", pageNumber=" + pageNumber
                + ", pageSize=" + pageSize
                + ']';
    }
}
