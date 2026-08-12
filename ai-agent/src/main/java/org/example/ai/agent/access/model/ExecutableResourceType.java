package org.example.ai.agent.access.model;

import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;

import java.util.Locale;

/**
 * 可执行资源类型。
 */
public enum ExecutableResourceType {

    /**
     * 业务能力定义。
     */
    CAPABILITY,

    /**
     * 工作流定义。
     */
    WORKFLOW;

    /**
     * 将接口参数转换为受支持的资源类型。
     */
    public static ExecutableResourceType from(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "资源类型不能为空"
            );
        }

        try {
            return ExecutableResourceType.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "不支持的资源类型：" + value
            );
        }
    }
}
