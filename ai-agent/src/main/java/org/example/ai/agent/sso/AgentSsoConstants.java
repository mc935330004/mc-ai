package org.example.ai.agent.sso;

/**
 * Agent单点登录公共常量。
 */
public final class AgentSsoConstants {

    /**
     * Agent完整管理端权限。
     */
    public static final String ADMIN_PERMISSION = "ai_agent_admin";

    /**
     * 普通用户聊天入口。
     */
    public static final String TARGET_CHAT = "CHAT";

    /**
     * 管理员完整管理端入口。
     */
    public static final String TARGET_ADMIN = "ADMIN";

    /**
     * Agent服务端会话Redis Key前缀。
     */
    public static final String SESSION_CACHE_PREFIX = "ai:agent:session:";

    /**
     * Agent会话Cookie名称。
     */
    public static final String SESSION_COOKIE_NAME = "AGENT_SESSION";

    private AgentSsoConstants() {
    }
}