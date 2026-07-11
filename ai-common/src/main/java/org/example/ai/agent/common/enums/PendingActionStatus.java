package org.example.ai.agent.common.enums;

import lombok.Getter;

/**
 * 待确认操作状态。
 *
 * 状态变更必须由后端控制，前端不能直接指定状态。
 */
@Getter
public enum PendingActionStatus {

    /**
     * 等待用户确认。
     */
    PENDING("PENDING", "待确认"),

    /**
     * 用户已经确认，等待执行。
     */
    CONFIRMED("CONFIRMED", "已确认"),

    /**
     * 正在调用业务系统。
     */
    EXECUTING("EXECUTING", "执行中"),

    /**
     * 业务系统执行成功。
     */
    SUCCESS("SUCCESS", "成功"),

    /**
     * 业务系统执行失败。
     */
    FAILED("FAILED", "失败"),

    /**
     * 用户主动取消。
     */
    CANCELLED("CANCELLED", "取消"),

    /**
     * 超过确认有效期。
     */
    EXPIRED("EXPIRED", "过期");

    private final String code;
    private final String name;

    PendingActionStatus(String code, String name) {
        this.code = code;
        this.name = name;
    }
}