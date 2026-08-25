package org.example.ai.agent.common.enums.protocol;

/**
 * 业务字段的展示值类型。
 *
 * 前端根据值类型进行确定性格式化，
 * 不依赖大模型决定金额、日期或状态的样式。
 */
public enum ValueType {

    /**
     * 普通文本。
     */
    TEXT,

    /**
     * 普通数字。
     */
    NUMBER,

    /**
     * 金额。
     */
    AMOUNT,

    /**
     * 百分比。
     */
    PERCENT,

    /**
     * 日期。
     */
    DATE,

    /**
     * 日期时间。
     */
    DATETIME,

    /**
     * 枚举状态。
     */
    ENUM,

    /**
     * 布尔值。
     */
    BOOLEAN,

    /**
     * 文件。
     */
    FILE,

    /**
     * 安全链接。
     */
    LINK
}