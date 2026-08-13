package org.example.ai.agent.stability;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 外部服务熔断配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.external-service-resilience")
public class ExternalServiceResilienceProperties {

    /**
     * 连续失败多少次后打开熔断器。
     */
    private int failureThreshold = 5;

    /**
     * 熔断后等待多久再允许探测，单位毫秒。
     */
    private long openDurationMs = 30_000L;

    /**
     * 启动时校验熔断配置。
     */
    @PostConstruct
    public void validate() {
        if (failureThreshold <= 0 || openDurationMs <= 0) {
            throw new IllegalStateException(
                    "外部服务熔断配置必须大于0"
            );
        }
    }
}
