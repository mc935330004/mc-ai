package org.example.ai.agent.security;

/**
 * 当前登录用户提供器。
 *
 * 后续接入 JWT、Spring Security 或业务系统登录态时，
 * 只需要替换该接口实现。
 */
public interface CurrentUserProvider {

    /**
     * 获取当前登录用户ID，不允许返回空值。
     */
    String getRequiredUserId();

    /**
     * 获取当前请求携带的认证信息
     */
    String getRequiredAuthorization();
}