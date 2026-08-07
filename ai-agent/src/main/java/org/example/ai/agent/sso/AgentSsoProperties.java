package org.example.ai.agent.sso;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Agent对接PM单点登录配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.sso")
public class AgentSsoProperties {

    /**
     * SSO总开关。
     */
    private boolean enabled = false;

    /**
     * PM后端地址。
     */
    private String pmBaseUrl;

    /**
     * PM Ticket交换接口。
     */
    private String exchangePath = "/ai-agent/sso/exchange";

    /**
     * Agent服务端客户端ID。
     */
    private String clientId;

    /**
     * Agent服务端客户端密钥。
     */
    private String clientSecret;

    /**
     * Agent会话最长有效时间，单位：秒。
     */
    private long sessionTtlSeconds = 1800L;

    /**
     * Agent会话Cookie名称。
     */
    private String sessionCookieName = "AGENT_SESSION";

    /**
     * 生产环境必须为true。
     */
    private boolean cookieSecure = true;

    /**
     * 同站点部署使用Lax。
     * 跨站点iframe需要None，同时cookieSecure必须为true。
     */
    private String cookieSameSite = "Lax";

    /**
     * 用于加密Redis中的PM Token。
     *
     * 必须是Base64编码后的32字节随机密钥。
     */
    private String tokenEncryptionKey;

    /**
     * PM登录页地址。
     */
    private String pmLoginUrl;

    /**
     * PM内部AI助手页面地址。
     */
    private String pmAiAssistantUrl;
    /**
     * 允许访问Agent接口的浏览器来源。
     *
     * 必须填写完整Origin，只包含协议、域名和端口，
     * 例如：http://192.168.8.251:5173
     *
     * 不要填写路径，不要以斜杠结尾。
     */
    private List<String> allowedBrowserOrigins = new ArrayList<>();

    /**
     * SSO启用时立即检查安全配置。
     *
     * 中文注释：
     * 配置错误应在系统启动时暴露，
     * 不能等用户登录时才发现密钥或Cookie配置错误。
     */
    @PostConstruct
    public void validateSsoConfiguration() {
        if (!enabled) {
            return;
        }

        if (!StringUtils.hasText(pmBaseUrl)) {
            throw new IllegalStateException(
                    "agent.sso.pm-base-url不能为空"
            );
        }

        if (!StringUtils.hasText(clientId)) {
            throw new IllegalStateException(
                    "agent.sso.client-id不能为空"
            );
        }

        if (!StringUtils.hasText(clientSecret)) {
            throw new IllegalStateException(
                    "agent.sso.client-secret不能为空"
            );
        }

        if (!StringUtils.hasText(tokenEncryptionKey)) {
            throw new IllegalStateException(
                    "agent.sso.token-encryption-key不能为空"
            );
        }

        byte[] encryptionKey;
        try {
            encryptionKey = Base64.getDecoder()
                    .decode(tokenEncryptionKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "agent.sso.token-encryption-key必须是有效的Base64",
                    exception
            );
        }

        if (encryptionKey.length != 32) {
            throw new IllegalStateException(
                    "agent.sso.token-encryption-key解码后必须是32字节"
            );
        }

        if (!Set.of("Strict", "Lax", "None")
                .contains(cookieSameSite)) {
            throw new IllegalStateException(
                    "agent.sso.cookie-same-site只能是Strict、Lax或None"
            );
        }

        if ("None".equals(cookieSameSite) && !cookieSecure) {
            throw new IllegalStateException(
                    "SameSite=None时cookie-secure必须为true"
            );
        }

        if (allowedBrowserOrigins == null
                || allowedBrowserOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "agent.sso.allowed-browser-origins不能为空"
            );
        }
    }
}