package org.example.ai.agent.business.subject.model;

import org.example.ai.agent.business.model.BusinessSubjectType;

import java.util.Objects;

/**
 * 已由来源系统权限过滤后的主体候选。
 *
 * subjectId 是后续确认选择使用的内部稳定标识，不进入字符串表示；人员工号只允许展示掩码。
 */
public record SubjectCandidate(
        BusinessSubjectType type,
        String subjectId,
        String displayName,
        String maskedEmployeeNo,
        String departmentPath,
        String projectCode,
        String projectType) {

    public SubjectCandidate {
        type = Objects.requireNonNull(type, "type不能为空");
        subjectId = requireText(subjectId, "subjectId不能为空");
        displayName = requireText(displayName, "displayName不能为空");
        maskedEmployeeNo = optionalText(maskedEmployeeNo);
        departmentPath = optionalText(departmentPath);
        projectCode = optionalText(projectCode);
        projectType = optionalText(projectType);
        if (type == BusinessSubjectType.PERSON
                && maskedEmployeeNo != null
                && !maskedEmployeeNo.contains("*")) {
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
