package org.example.ai.agent.sso.model;

import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * Agent服务端安全会话。
 *
 * 注意：
 * 不使用@Data，避免toString输出敏感信息。
 */
@Getter
@Setter
public class AgentSession {

    /**
     * PM用户主键。
     */
    private Long pmUserId;

    /**
     * PM登录账号。
     *
     * 当前聊天记录仍使用username隔离，保持之前逻辑不变。
     */
    private String username;

    /**
     * PM租户主键。
     */
    private Long tenantId;

    private Long deptId;

    /**
     * 登录时的权限快照。
     *
     * 管理接口执行时仍会请求PM获取最新权限。
     */
    private Set<String> permissions = new HashSet<>();

    /**
     * CHAT或者ADMIN。
     */
    private String target;

    /**
     * AES-GCM加密后的PM Token。
     */
    private String encryptedPmAccessToken;

    /**
     * Agent会话过期时间，Unix毫秒。
     */
    private Long expiresAt;
}