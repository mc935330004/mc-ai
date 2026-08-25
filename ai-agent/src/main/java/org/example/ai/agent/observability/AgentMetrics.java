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
    private static final String STREAM_VERSION = "v3";
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
     * 记录SSE连接建立。
     */
    public void recordSseOpened() {
        activeSseConnections.incrementAndGet();
        Counter.builder("agent.sse.connections")
                .description("Agent SSE连接数量")
                .tag("action", "opened")
                .tag("version", STREAM_VERSION)
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录SSE连接关闭。
     */
    public void recordSseClosed(String reason) {
        activeSseConnections.updateAndGet(
                value -> Math.max(value - 1, 0)
        );
        Counter.builder("agent.sse.connections")
                .tag("action", "closed")
                .tag("version", STREAM_VERSION)
                .tag("reason", safeTag(reason))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录SSE事件。
     */
    public void recordSseEvent(String eventType) {
        Counter.builder("agent.sse.events")
                .description("Agent SSE事件数量")
                .tag("version", STREAM_VERSION)
                .tag("type", safeTag(eventType))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录首个有效内容耗时。
     */
    public void recordFirstContentDuration(long durationMs) {

        Timer.builder(
                        "agent.answer.first.content.duration"
                )
                .description("Agent首个有效内容耗时")
                .tag("version", STREAM_VERSION)
                .register(meterRegistry)
                .record(
                        Math.max(durationMs, 0),
                        TimeUnit.MILLISECONDS
                );
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

    /**
     * 记录一次AI报告分析尝试。
     *
     * success=true表示模型结果已经通过本地解析和可信数据校验。
     */
    public void recordReportAnalysisAttempt(
            boolean success,
            String reason,
            long durationMs) {

        String successTag = success ? "true" : "false";

        Counter.builder("agent.report.analysis.attempts")
                .description("AI报告分析尝试次数")
                .tag("source", "AI")
                .tag("success", successTag)
                .tag("reason", safeTag(reason))
                .register(meterRegistry)
                .increment();

        Timer.builder("agent.report.analysis.attempt.duration")
                .description("AI报告分析尝试耗时")
                .tag("success", successTag)
                .tag("reason", safeTag(reason))
                .register(meterRegistry)
                .record(
                        Math.max(durationMs, 0),
                        TimeUnit.MILLISECONDS
                );
    }

    /**
     * 记录最终可展示的报告分析结果。
     *
     * source只允许AI或RULE_FALLBACK，
     * reason使用固定分类，禁止传入异常原文。
     */
    public void recordReportAnalysisCompleted(String source, String reason, long durationMs) {
        Counter.builder("agent.report.analysis.completed")
                .description("最终可展示的报告分析数量")
                .tag("source", safeTag(source))
                .tag("reason", safeTag(reason))
                .register(meterRegistry)
                .increment();

        Timer.builder("agent.report.analysis.completed.duration")
                .description("报告分析最终完成耗时")
                .tag("source", safeTag(source))
                .register(meterRegistry)
                .record(
                        Math.max(durationMs, 0),
                        TimeUnit.MILLISECONDS
                );
    }

    private String safeTag(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : "UNKNOWN";
    }
}