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
     * 已确认业务系统没有执行成功。
     */
    FAILED("FAILED", "失败"),

    /**
     * 请求已经发出，但无法判断业务系统是否执行成功。
     */
    UNKNOWN("UNKNOWN", "结果待确认"),

    /**
     * 确认条件发生变化，拒绝执行。
     */
    REJECTED("REJECTED", "已拒绝"),

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