package org.example.ai.agent.common.enums;

/**
 * 模型调用失败分类。
 *
 * 分类结果决定是否切换备用模型，
 * 同时控制故障模型的短暂抑制时间。
 */
public enum ModelFailureCategory {

    QUOTA_EXHAUSTED(
            true,
            300_000L,
            "模型额度不足"
    ),
    RATE_LIMIT(
            true,
            30_000L,
            "模型服务请求过于频繁"
    ),
    TIMEOUT(
            true,
            20_000L,
            "模型调用超时"
    ),
    CONNECTION_ERROR(
            true,
            20_000L,
            "无法连接模型服务"
    ),
    PROVIDER_5XX(
            true,
            30_000L,
            "模型供应商服务暂时不可用"
    ),
    AUTHENTICATION_ERROR(
            false,
            0L,
            "模型凭证无效或没有权限"
    ),
    BAD_REQUEST(
            false,
            0L,
            "模型请求参数不正确"
    ),
    SAFETY_REJECTION(
            false,
            0L,
            "模型安全策略拒绝当前请求"
    ),
    BUSINESS_ERROR(
            false,
            0L,
            "业务处理失败"
    ),
    CANCELLED(
            false,
            0L,
            "模型调用已取消"
    ),
    UNKNOWN(
            false,
            0L,
            "模型调用发生未知错误"
    );

    private final boolean failoverAllowed;
    private final long suppressMillis;
    private final String safeMessage;

    ModelFailureCategory(
            boolean failoverAllowed,
            long suppressMillis,
            String safeMessage) {

        this.failoverAllowed = failoverAllowed;
        this.suppressMillis = suppressMillis;
        this.safeMessage = safeMessage;
    }

    public boolean failoverAllowed() {
        return failoverAllowed;
    }

    public long suppressMillis() {
        return suppressMillis;
    }

    public String safeMessage() {
        return safeMessage;
    }
}