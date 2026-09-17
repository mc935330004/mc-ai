package org.example.ai.agent.business.subject.model;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.common.model.ProjectRelationship;

import java.util.Objects;

/**
 * 仅在目录与主体定位服务内部传递的授权候选。
 * 禁止直接作为接口响应或模型上下文序列化。
 */
@JsonIgnoreType
public record AuthorizedSubjectCandidate(
        BusinessSubjectType type,
        String rawSubjectId,
        String displayName,
        String maskedEmployeeNo,
        String departmentPath,
        String projectCode,
        String projectType,
        ProjectRelationship projectRelationship,
        String projectStatus) {

    public AuthorizedSubjectCandidate {
        type = Objects.requireNonNull(
                type,
                "type不能为空"
        );

        rawSubjectId = requireText(
                rawSubjectId,
                "rawSubjectId不能为空"
        );

        displayName = requireText(
                displayName,
                "displayName不能为空"
        );

        projectStatus = optionalText(projectStatus);

        if (type != BusinessSubjectType.PROJECT
                && (projectRelationship != null
                || projectStatus != null)) {
            throw new IllegalArgumentException(
                    "非项目主体不能携带项目关系或状态"
            );
        }
    }

    /**
     * 兼容现有人员、部门和精确项目定位代码。
     */
    public AuthorizedSubjectCandidate(
            BusinessSubjectType type,
            String rawSubjectId,
            String displayName,
            String maskedEmployeeNo,
            String departmentPath,
            String projectCode,
            String projectType) {
        this(
                type,
                rawSubjectId,
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
     * 内部对象的字符串表示不输出候选字段值。
     */
    @Override
    public String toString() {
        return "AuthorizedSubjectCandidate["
                + "type=" + type
                + ", projectRelationshipPresent="
                + (projectRelationship != null)
                + ", projectStatusPresent="
                + (projectStatus != null)
                + ']';
    }
}