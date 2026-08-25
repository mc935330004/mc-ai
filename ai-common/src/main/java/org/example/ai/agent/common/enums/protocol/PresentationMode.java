package org.example.ai.agent.common.enums.protocol;

/**
 * AI回答的展示模式。
 *
 * AUTO只用于配置和路由判断，
 * 最终响应只能是CHAT或REPORT。
 */
public enum PresentationMode {

    /**
     * 普通聊天回答。
     */
    CHAT,

    /**
     * 完整业务报告。
     */
    REPORT,

    /**
     * 根据用户问题和工作流配置自动判断。
     */
    AUTO
}