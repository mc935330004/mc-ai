package org.example.ai.agent.common.enums;

/**
 * 告警严重等级。
 */
public enum AlertSeverity {

    /**
     * 普通提示，不影响核心业务。
     */
    INFO,

    /**
     * 警告，需要关注但不一定立即处理。
     */
    WARNING,

    /**
     * 执行错误，需要人工排查。
     */
    ERROR,

    /**
     * 严重故障，需要优先处理。
     */
    CRITICAL
}