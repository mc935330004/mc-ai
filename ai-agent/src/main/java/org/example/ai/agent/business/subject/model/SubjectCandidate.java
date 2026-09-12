package org.example.ai.agent.business.subject.model;

import org.example.ai.agent.business.model.BusinessSubjectType;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 已由来源系统权限过滤后的主体候选。
 *
 * selectionToken 是与用户、会话和主体类型绑定的短期不透明令牌；人员工号只允许展示严格掩码。
 */
public record SubjectCandidate(
        BusinessSubjectType type,
        String selectionToken,
        String displayName,
        String maskedEmployeeNo,
        String departmentPath,
        String projectCode,
        String projectType) {

    private static final Pattern MASKED_EMPLOYEE_NO = Pattern.compile(
            "^[\\p{L}\\p{N}]{1,4}\\*{3,8}[\\p{L}\\p{N}]{1,4}$"
    );

    public SubjectCandidate {
        type = Objects.requireNonNull(type, "type不能为空");
        selectionToken = requireText(selectionToken, "selectionToken不能为空");
        displayName = requireText(displayName, "displayName不能为空");
        maskedEmployeeNo = optionalText(maskedEmployeeNo);
        departmentPath = optionalText(departmentPath);
        projectCode = optionalText(projectCode);
        projectType = optionalText(projectType);
        if (type == BusinessSubjectType.PERSON
                && (maskedEmployeeNo == null
                || !MASKED_EMPLOYEE_NO.matcher(maskedEmployeeNo).matches())) {
            throw new IllegalArgumentException("人员工号必须先脱敏");
        }
        if (type != BusinessSubjectType.PERSON && maskedEmployeeNo != null) {
            throw new IllegalArgumentException("非人员候选不能携带员工工号");
        }
        if (type == BusinessSubjectType.PROJECT && projectCode == null) {
            throw new IllegalArgumentException("项目候选必须携带项目编码");
        }
        if (type != BusinessSubjectType.PROJECT
                && (projectCode != null || projectType != null)) {
            throw new IllegalArgumentException("非项目候选不能携带项目字段");
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 不输出内部主体标识，避免员工工号等稳定标识被日志意外记录。
     */
    @Override
    public String toString() {
        return "SubjectCandidate["
                + "type=" + type
                + ", displayNamePresent=true"
                + ", employeeNoMasked=" + (maskedEmployeeNo != null)
                + ", departmentPathPresent=" + (departmentPath != null)
                + ", projectCodePresent=" + (projectCode != null)
                + ", projectTypePresent=" + (projectType != null)
                + ']';
    }
}
