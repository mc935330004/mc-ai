package org.example.ai.agent.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务助手多人查询的资源边界。
 *
 * 独立配置避免复用 SSE 线程池参数，防止聊天连接与下游业务查询互相挤占资源。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.business-assistant")
public class BusinessAssistantProperties {

    private int maxPeople = 100;
    private int concurrency = 5;
    private int requestsPerSecond = 10;
    private Duration personTimeout = Duration.ofSeconds(20);
    private Duration overallTimeout = Duration.ofSeconds(120);
    private int maxRetries = 1;
    private Duration snapshotMaxTtl = Duration.ofHours(24);

    /** 启动时拒绝可能造成无限扇出的配置。 */
    @PostConstruct
    public void validate() {
        boolean invalid = maxPeople <= 0
                || concurrency <= 0
                || concurrency > maxPeople
                || requestsPerSecond <= 0
                || personTimeout == null
                || personTimeout.isZero()
                || personTimeout.isNegative()
                || overallTimeout == null
                || overallTimeout.isZero()
                || overallTimeout.isNegative()
                || maxRetries < 0
                || snapshotMaxTtl == null
                || snapshotMaxTtl.isZero()
                || snapshotMaxTtl.isNegative()
                || snapshotMaxTtl.compareTo(Duration.ofHours(24)) > 0;
        if (invalid) {
            throw new IllegalStateException("业务助手多人查询配置不合法");
        }
    }
}
