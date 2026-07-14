package org.example.ai.agent.chat.support;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.AgentStreamProperties;
import org.example.ai.agent.observability.AgentMetrics;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent SSE 会话工厂。
 */
@Component
@RequiredArgsConstructor
public class AgentStreamSessionFactory {

    private final AgentStreamProperties properties;
    private final MarkdownChunker markdownChunker;
    private final AgentMetrics agentMetrics;
    /**
     * 创建单次请求专用的 SSE Session。
     */
    public AgentStreamSession create(String runId,Integer streamVersion) {
        SseEmitter emitter = new SseEmitter( properties.getTimeoutMs() );

        AgentStreamSession session = new AgentStreamSession(
                        emitter,
                        runId,
                        streamVersion,
                        properties,
                        markdownChunker,
                        agentMetrics);

        // 超时时由Session发送ERROR并关闭连接。
        emitter.onTimeout(session::timeout);
        // 客户端提前断开时更新活跃连接指标。
        emitter.onCompletion(session::connectionClosed);
        emitter.onError(throwable ->session.connectionClosed());
        return session;
    }
}