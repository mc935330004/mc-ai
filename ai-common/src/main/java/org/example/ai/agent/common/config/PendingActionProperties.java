package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 待确认操作配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.pending-action")
public class PendingActionProperties {

    /**
     * 用户确认有效期，单位分钟。
     */
    private long confirmTimeoutMinutes = 10;
}