package org.example.ai.agent.sso;

import org.junit.jupiter.api.Test;
import org.example.ai.agent.common.config.BusinessApiProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 生产SSO安全配置校验测试。
 */
class ProductionSsoSecurityValidatorTest {

    @Test
    void shouldAcceptHttpsAndSecureCookie() {
        AgentSsoProperties properties = secureProperties();

        ProductionSsoSecurityValidator validator =
                new ProductionSsoSecurityValidator(
                        properties,
                        secureBusinessApiProperties()
                );

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void shouldRejectInsecureProductionCookie() {
        AgentSsoProperties properties = secureProperties();
        properties.setCookieSecure(false);

        ProductionSsoSecurityValidator validator =
                new ProductionSsoSecurityValidator(
                        properties,
                        secureBusinessApiProperties()
                );

        assertThrows(
                IllegalStateException.class,
                validator::validate
        );
    }

    @Test
    void shouldRejectHttpProductionOrigin() {
        AgentSsoProperties properties = secureProperties();
        properties.setAllowedBrowserOrigins(
                List.of("http://ai.example.com")
        );

        ProductionSsoSecurityValidator validator =
                new ProductionSsoSecurityValidator(
                        properties,
                        secureBusinessApiProperties()
                );

        assertThrows(
                IllegalStateException.class,
                validator::validate
        );
    }

    @Test
    void shouldRejectOriginWithPath() {
        AgentSsoProperties properties = secureProperties();
        properties.setAllowedBrowserOrigins(
                List.of("https://ai.example.com/chat")
        );

        ProductionSsoSecurityValidator validator =
                new ProductionSsoSecurityValidator(
                        properties,
                        secureBusinessApiProperties()
                );

        assertThrows(
                IllegalStateException.class,
                validator::validate
        );
    }

    @Test
    void shouldRejectServiceUrlWithUserCredentials() {
        BusinessApiProperties businessApiProperties =
                secureBusinessApiProperties();
        businessApiProperties.setBaseUrl(
                "https://user:password@pm.example.com/pm"
        );

        ProductionSsoSecurityValidator validator =
                new ProductionSsoSecurityValidator(
                        secureProperties(),
                        businessApiProperties
                );

        assertThrows(
                IllegalStateException.class,
                validator::validate
        );
    }

    /**
     * 构建满足生产安全约束的基础配置。
     */
    private AgentSsoProperties secureProperties() {
        AgentSsoProperties properties = new AgentSsoProperties();
        properties.setEnabled(true);
        properties.setCookieSecure(true);
        properties.setPmBaseUrl("https://pm.example.com");
        properties.setPmLoginUrl("https://pm.example.com/login");
        properties.setPmAiAssistantUrl("https://ai.example.com");
        properties.setAllowedBrowserOrigins(
                List.of("https://ai.example.com")
        );
        return properties;
    }

    /**
     * 构建生产HTTPS业务接口配置。
     */
    private BusinessApiProperties secureBusinessApiProperties() {
        BusinessApiProperties properties =
                new BusinessApiProperties();
        properties.setBaseUrl("https://pm.example.com/pm");
        return properties;
    }
}
