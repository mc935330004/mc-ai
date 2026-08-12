package org.example.ai.agent.access.model;

import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;

import java.util.Locale;

/**
 * 可执行资源访问范围。
 */
public enum ResourceAccessScope {

    /**
     * 所有已登录人员可以运行。
     */
    PUBLIC,

    /**
     * 只有授权名单中的人员可以运行。
     */
    RESTRICTED;

    /**
     * 将接口参数转换为受支持的访问范围。
     */
    public static ResourceAccessScope from(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "访问范围不能为空"
            );
        }

        try {
            return ResourceAccessScope.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "访问范围只允许PUBLIC或RESTRICTED"
            );
        }
    }
}
