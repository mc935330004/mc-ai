package org.example.ai.agent.sso.vo;

import lombok.Builder;
import lombok.Getter;

/**
 * 返回给Agent前端的安全用户信息。
 *
 * 不返回PM Token和全部权限列表。
 */
@Getter
@Builder
public class AgentCurrentUserVO {

    /**
     * PM用户主键。
     */
    private Long pmUserId;

    /**
     * PM登录账号。
     */
    private String username;

    /**
     * CHAT或者ADMIN。
     */
    private String target;

    /**
     * 是否允许访问Agent完整管理端。
     */
    private boolean agentAdmin;

    /**
     * 当前会话ID。
     *
     * 仅在SSO登录(exchange)接口返回，
     * 供跨站iframe场景下前端通过X-Agent-Session请求头传递会话。
     */
    private String sessionId;
}