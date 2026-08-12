package org.example.ai.agent.modules.knowledgebase.model;

import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * 知识库文档访问范围。
 */
public enum KnowledgeDocumentAccessScope {

    /**
     * 当前租户内公开。
     */
    PUBLIC,

    /**
     * 当前租户内仅归属部门可访问。
     */
    DEPARTMENT;

    /**
     * 解析并校验访问范围。
     */
    public static KnowledgeDocumentAccessScope from(String value) {
        if (!StringUtils.hasText(value)) {
            return PUBLIC;
        }

        try {
            return KnowledgeDocumentAccessScope.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "不支持的知识文档访问范围：" + value
            );
        }
    }
}