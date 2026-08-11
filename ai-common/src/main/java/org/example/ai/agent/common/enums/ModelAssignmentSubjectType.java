package org.example.ai.agent.common.enums;

import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;

/**
 * 模型授权对象类型。
 */
public enum ModelAssignmentSubjectType {
    SYSTEM,
    USER;
    public static ModelAssignmentSubjectType from(
            String value) {

        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型授权对象类型不能为空"
            );
        }

        try {
            return ModelAssignmentSubjectType.valueOf(
                    value.trim().toUpperCase()
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "不支持的模型授权对象类型：" + value
            );
        }
    }
}