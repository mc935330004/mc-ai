package org.example.ai.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 实时监控指标。
 *
 * 注意：
 * 指标标签中禁止放入 runId、userId、问题内容等高基数字段。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 当前存活的 SSE 连接数。
     */
    private final AtomicInteger activeSseConnections = new AtomicInteger();

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder("agent.sse.connections.active",activeSseConnections,
                        AtomicInteger::get)
                .description("当前活跃的Agent SSE连接数")
                .register(meterRegistry);
    }

    /**
     * 记录 SSE 连接建立。
     */
    public void recordSseOpened(int protocolVersion) {
        activeSseConnections.incrementAndGet();
        Counter.builder("agent.sse.connections")
                .description("Agent SSE连接数量")
                .tag("action", "opened")
                .tag("version", version(protocolVersion))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录 SSE 连接关闭。
     */
    public void recordSseClosed( int protocolVersion,String reason ) {
        activeSseConnections.updateAndGet(
                value -> Math.max(value - 1, 0)
        );

        Counter.builder("agent.sse.connections")
                .tag("action", "closed")
                .tag("version", version(protocolVersion))
                .tag("reason", safeTag(reason))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录 SSE 事件数量。
     */
    public void recordSseEvent( int protocolVersion, String eventType) {
        Counter.builder("agent.sse.events")
                .description("Agent SSE事件数量")
                .tag("version", version(protocolVersion))
                .tag("type", safeTag(eventType))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录最终回答字符数。
     */
    public void recordAnswerLength(int protocolVersion,int contentLength) {
        DistributionSummary.builder("agent.answer.length")
                .description("Agent最终回答字符数")
                .baseUnit("characters")
                .tag("version", version(protocolVersion))
                .register(meterRegistry)
                .record(Math.max(contentLength, 0));
    }

    /**
     * 记录首个有效内容耗时。
     */
    public void recordFirstContentDuration(int protocolVersion,long durationMs) {
        Timer.builder("agent.answer.first.content.duration")
                .description("Agent首个有效内容耗时")
                .tag("version", version(protocolVersion))
                .register(meterRegistry)
                .record(Math.max(durationMs, 0),TimeUnit.MILLISECONDS);
    }

    /**
     * 记录整次 Agent 运行结果。
     */
    public void recordRun(String status,long durationMs) {
        Counter.builder("agent.run.total")
                .tag("status", safeTag(status))
                .register(meterRegistry)
                .increment();

        Timer.builder("agent.run.duration")
                .tag("status", safeTag(status))
                .register(meterRegistry)
                .record(Math.max(durationMs, 0),TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一次模型调用。
     */
    public void recordModelCall(String callType,boolean success,long durationMs,int totalTokens) {
        String successTag = success ? "true" : "false";

        Counter.builder("agent.model.calls")
                .tag("call.type", safeTag(callType))
                .tag("success", successTag)
                .register(meterRegistry)
                .increment();

        Timer.builder("agent.model.call.duration")
                .tag("call.type", safeTag(callType))
                .tag("success", successTag)
                .register(meterRegistry)
                .record(Math.max(durationMs, 0),TimeUnit.MILLISECONDS );

        DistributionSummary.builder("agent.model.tokens")
                .baseUnit("tokens")
                .tag("call.type", safeTag(callType))
                .tag("success", successTag)
                .register(meterRegistry)
                .record(Math.max(totalTokens, 0));
    }

    private String version(int protocolVersion) {
        return protocolVersion == 2 ? "v2" : "v1";
    }

    private String safeTag(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : "UNKNOWN";
    }
}