package org.example.ai.agent.business.subject.model;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.common.model.ProjectRelationship;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 已由来源系统权限过滤后的安全主体候选。
 *
 * selectionToken与用户、会话和主体类型绑定，
 * 前端只能使用该凭证选择主体。
 */
public record SubjectCandidate(
        BusinessSubjectType type,
        String selectionToken,
        String displayName,
        String maskedEmployeeNo,
        String departmentPath,
        String projectCode,
        String projectType,
        ProjectRelationship projectRelationship,
        String projectStatus) {

    private static final Pattern MASKED_EMPLOYEE_NO =
            Pattern.compile(
                    "^[\\p{L}\\p{N}]{1,4}"
                            + "\\*{3,8}"
                            + "[\\p{L}\\p{N}]{1,4}$"
            );

    public SubjectCandidate {
        type = Objects.requireNonNull(
                type,
                "type不能为空"
        );

        selectionToken = requireText(
                selectionToken,
                "selectionToken不能为空"
        );

        displayName = requireText(
                displayName,
                "displayName不能为空"
        );

        maskedEmployeeNo =
                optionalText(maskedEmployeeNo);
        departmentPath =
                optionalText(departmentPath);
        projectCode =
                optionalText(projectCode);
        projectType =
                optionalText(projectType);
        projectStatus =
                optionalText(projectStatus);

        if (type == BusinessSubjectType.PERSON
                && (maskedEmployeeNo == null
                || !MASKED_EMPLOYEE_NO
                .matcher(maskedEmployeeNo)
                .matches())) {
            throw new IllegalArgumentException(
                    "人员工号必须先脱敏"
            );
        }

        if (type != BusinessSubjectType.PERSON
                && maskedEmployeeNo != null) {
            throw new IllegalArgumentException(
                    "非人员候选不能携带员工工号"
            );
        }

        if (type == BusinessSubjectType.PROJECT
                && projectCode == null) {
            throw new IllegalArgumentException(
                    "项目候选必须携带项目编码"
            );
        }

        if (type != BusinessSubjectType.PROJECT
                && (projectCode != null
                || projectType != null
                || projectRelationship != null
                || projectStatus != null)) {
            throw new IllegalArgumentException(
                    "非项目候选不能携带项目字段"
            );
        }
    }

    /**
     * 兼容现有构造代码。
     */
    public SubjectCandidate(
            BusinessSubjectType type,
            String selectionToken,
            String displayName,
            String maskedEmployeeNo,
            String departmentPath,
            String projectCode,
            String projectType) {
        this(
                type,
                selectionToken,
                displayName,
                maskedEmployeeNo,
                departmentPath,
                projectCode,
                projectType,
                null,
                null
        );
    }

    private static String requireText(
            String value,
            String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim();
    }

    /**
     * 不输出令牌、主体标识或项目业务字段。
     */
    @Override
    public String toString() {
        return "SubjectCandidate["
                + "type=" + type
                + ", displayNamePresent=true"
                + ", employeeNoMasked="
                + (maskedEmployeeNo != null)
                + ", departmentPathPresent="
                + (departmentPath != null)
                + ", projectCodePresent="
                + (projectCode != null)
                + ", projectTypePresent="
                + (projectType != null)
                + ", projectRelationshipPresent="
                + (projectRelationship != null)
                + ", projectStatusPresent="
                + (projectStatus != null)
                + ']';
    }
}