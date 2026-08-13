package org.example.ai.agent.stability;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent接口限流配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.agent.rate-limit")
public class RequestRateLimitProperties {

    /**
     * 是否启用接口限流。
     */
    private boolean enabled = true;

    /**
     * 单个用户每分钟允许的普通请求数。
     */
    private int defaultRequestsPerMinute = 120;

    /**
     * 单个用户每分钟允许的高成本请求数。
     */
    private int expensiveRequestsPerMinute = 20;

    /**
     * Redis限流键前缀。
     */
    private String keyPrefix = "mc-ai:rate-limit:";

    /**
     * 启动时校验限流配置。
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (defaultRequestsPerMinute <= 0
                || expensiveRequestsPerMinute <= 0) {
            throw new IllegalStateException(
                    "Agent接口限流阈值必须大于0"
            );
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalStateException(
                    "Agent接口限流Redis键前缀不能为空"
            );
        }
    }
}
