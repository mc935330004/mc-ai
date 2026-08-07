package org.example.ai.agent.security;

/**
 * 当前登录用户提供器。
 */
public interface CurrentUserProvider {

    /**
     * 获取当前登录用户编码。
     */
    String getRequiredUserId();

    /**
     * 获取PM登录凭证。
     */
    String getRequiredAuthorization();

    /**
     * 校验当前PM用户是否具有指定权限。
     */
    void requirePermission(String permission);

    /**
     * 判断当前PM用户是否具有指定权限。
     */
    boolean hasPermission(String permission);
}