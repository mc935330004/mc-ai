package org.example.ai.agent.common.enums;

/**
 * 告警处理状态。
 */
public enum AlertStatus {

    /**
     * 新产生、尚未确认的告警。
     */
    OPEN,

    /**
     * 已有人员确认并开始处理。
     */
    ACKNOWLEDGED,

    /**
     * 问题已经解决。
     */
    RESOLVED
}