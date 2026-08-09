package org.example.ai.agent.common.enums;

import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;

/**
 * 模型接口类型。
 *
 * 第一阶段只实现OpenAI兼容协议，
 * 不提前声明尚未实现的供应商协议。
 */
public enum ModelApiType {

    OPENAI_COMPATIBLE;

    public static ModelApiType from(String value) {
        if (value == null || value.isBlank()) {
            return OPENAI_COMPATIBLE;
        }

        try {
            return ModelApiType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "当前不支持模型接口类型：" + value
            );
        }
    }
}