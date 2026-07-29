package org.example.ai.agent.security;

/**
 * 当前登录用户提供器。
 *
 * 后续接入 JWT、Spring Security 或业务系统登录态时，
 * 只需要替换该接口实现。
 */
public interface CurrentUserProvider {

    /**
     * 获取当前登录用户ID。
     */
    String getRequiredUserId();

    /**
     * 获取当前请求携带的PM登录凭证。
     */
    String getRequiredAuthorization();

    /**
     * 校验当前PM用户是否具有指定权限。
     *
     * @param permission PM权限编码
     */
    void requirePermission(String permission );
}