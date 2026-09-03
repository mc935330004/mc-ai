package org.example.ai.agent.business.subject.model;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import org.example.ai.agent.business.model.BusinessSubjectType;

import java.util.Objects;

/**
 * 仅在主体目录与定位服务内部传递的授权候选，禁止作为接口响应或模型上下文序列化。
 */
@JsonIgnoreType
public record AuthorizedSubjectCandidate(
        BusinessSubjectType type,
        String rawSubjectId,
        String displayName,
        String maskedEmployeeNo,
        String departmentPath,
        String projectCode,
        String projectType) {

    public AuthorizedSubjectCandidate {
        type = Objects.requireNonNull(type, "type不能为空");
        rawSubjectId = requireText(rawSubjectId, "rawSubjectId不能为空");
        displayName = requireText(displayName, "displayName不能为空");
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /**
     * 内部对象的字符串表示也不输出任何候选字段值。
     */
    @Override
    public String toString() {
        return "AuthorizedSubjectCandidate[type=" + type + ']';
    }
}
