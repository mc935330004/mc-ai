package org.example.ai.agent.sso.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * Agent从PM后端获取的可信用户身份。
 */
@Getter
@Setter
public class PmSsoIdentity {

    /**
     * PM用户主键。
     */
    private Long userId;

    /**
     * PM登录账号。
     */
    private String username;

    /**
     * PM租户ID。
     */
    private Long tenantId;

    /**
     * PM部门ID。
     */
    private Long deptId;

    /**
     * PM权限码集合。
     */
    private Set<String> permissions = new HashSet<>();

    /**
     * CHAT或者ADMIN。
     */
    private String target;

    /**
     * PM Access Token。
     *
     * 禁止输出到日志和前端。
     */
    private String pmAccessToken;

    /**
     * PM Token过期时间，Unix时间戳，单位：秒。
     */
    private Long pmTokenExpiresAt;

    /**
     * 判断当前用户是否拥有Agent管理权限。
     */
    public boolean hasAgentAdminPermission() {
        return permissions != null
                && permissions.contains("ai_agent_admin");
    }
}