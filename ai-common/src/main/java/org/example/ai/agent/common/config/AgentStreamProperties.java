package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Agent SSE 流配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.stream")
public class AgentStreamProperties {

    /**
     * SSE 最大连接时间，单位毫秒。
     */
    private long timeoutMs = 180_000L;

    /**
     * Markdown 建议分块大小。
     */
    private int chunkSize = 200;

    /**
     * 当前默认协议版本。
     *
     * 1：兼容原有 answer/done 事件。
     * 2：使用 answer_start/delta/snapshot/done。
     */
    private int defaultVersion = 1;

    /**
     * 是否发送最终完整快照。
     */
    private boolean snapshotEnabled = true;

    /**
     * Agent 后台执行线程池核心线程数。
     */
    private int executorCoreSize = 4;

    /**
     * Agent 后台执行线程池最大线程数。
     */
    private int executorMaxSize = 16;

    /**
     * Agent 后台执行队列容量。
     */
    private int executorQueueCapacity = 200;

    /**
     * 是否允许使用 SSE v2。
     *
     * 紧急回滚时设为 false，
     * 所有请求都会回退到 v1。
     */
    private boolean v2Enabled = true;

    /**
     * SSE v2 灰度百分比，范围为0～100。
     */
    private int v2RolloutPercentage = 100;

    /**
     * 强制使用 v2 的用户。
     *
     * 适合开发人员和测试人员提前验证。
     */
    private Set<String> v2AllowUsers = new HashSet<>();

    /**
     * 强制回退 v1 的用户。
     */
    private Set<String> v2DenyUsers = new HashSet<>();
}