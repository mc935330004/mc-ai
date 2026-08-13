package org.example.ai.agent.sso;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.example.ai.agent.common.config.BusinessApiProperties;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * 生产环境SSO安全配置校验。
 */
@Component
@Profile("prod")
public class ProductionSsoSecurityValidator {

    private final AgentSsoProperties properties;
    private final BusinessApiProperties businessApiProperties;

    ProductionSsoSecurityValidator(
            AgentSsoProperties properties,
            BusinessApiProperties businessApiProperties) {
        this.properties = properties;
        this.businessApiProperties = businessApiProperties;
    }

    /**
     * 生产配置不满足HTTPS与安全Cookie要求时阻止应用启动。
     */
    @PostConstruct
    public void validate() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException(
                    "生产环境必须启用Agent SSO"
            );
        }
        if (!properties.isCookieSecure()) {
            throw new IllegalStateException(
                    "生产环境agent.sso.cookie-secure必须为true"
            );
        }

        requireHttps(
                "agent.sso.pm-base-url",
                properties.getPmBaseUrl()
        );
        requireHttps(
                "agent.sso.pm-login-url",
                properties.getPmLoginUrl()
        );
        requireHttps(
                "agent.sso.pm-ai-assistant-url",
                properties.getPmAiAssistantUrl()
        );
        requireHttps(
                "agent.business-api.base-url",
                businessApiProperties.getBaseUrl()
        );

        List<String> origins = properties.getAllowedBrowserOrigins();
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException(
                    "生产环境必须配置允许访问的浏览器Origin"
            );
        }
        origins.forEach(origin -> requireHttpsOrigin(
                "agent.sso.allowed-browser-origins",
                origin
        ));
    }

    /**
     * 校验生产地址必须使用HTTPS。
     */
    private void requireHttps(String name, String value) {
        URI uri = parseHttpsUri(name, value);
        if (!StringUtils.hasText(uri.getHost())
                || StringUtils.hasText(uri.getUserInfo())) {
            throw new IllegalStateException(
                    name + "在生产环境必须包含有效主机名且不能携带用户凭据"
            );
        }
    }

    /**
     * 浏览器Origin只能包含协议、主机和可选端口。
     */
    private void requireHttpsOrigin(String name, String value) {
        URI uri = parseHttpsUri(name, value);
        boolean invalidOrigin = !StringUtils.hasText(uri.getHost())
                || StringUtils.hasText(uri.getUserInfo())
                || StringUtils.hasText(uri.getQuery())
                || StringUtils.hasText(uri.getFragment())
                || (StringUtils.hasText(uri.getPath())
                && !"/".equals(uri.getPath()));
        if (invalidOrigin || value.trim().endsWith("/")) {
            throw new IllegalStateException(
                    name + "必须是无路径、无末尾斜杠的HTTPS Origin"
            );
        }
    }

    /**
     * 解析并校验生产环境HTTPS地址。
     */
    private URI parseHttpsUri(String name, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(name + "不能为空");
        }
        try {
            URI uri = new URI(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException(
                        name + "在生产环境必须使用https://地址"
                );
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    name + "不是合法地址",
                    exception
            );
        }
    }
}
