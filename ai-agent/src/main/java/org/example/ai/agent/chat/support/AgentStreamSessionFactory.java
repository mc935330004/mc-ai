package org.example.ai.agent.chat.support;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.stream.ResponseChecksumService;
import org.example.ai.agent.common.config.AgentStreamProperties;
import org.example.ai.agent.observability.AgentMetrics;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent SSE会话工厂。
 */
@Component
@RequiredArgsConstructor
public class AgentStreamSessionFactory {

    private final AgentStreamProperties properties;
    private final AgentMetrics agentMetrics;
    private final ResponseChecksumService responseChecksumService;

    /**
     * 创建单次请求专用的SSE会话。
     */
    public AgentStreamSession create(String runId, String conversationId) {
        SseEmitter emitter = new SseEmitter(properties.getTimeoutMs());
        AgentStreamSession session = new AgentStreamSession(emitter, runId, conversationId, agentMetrics, responseChecksumService);
        // 超时后由会话统一发送安全错误。
        emitter.onTimeout(session::timeout);
        // 客户端关闭页面或刷新时更新连接状态。
        emitter.onCompletion(session::connectionClosed);
        emitter.onError(throwable -> session.connectionClosed());
        return session;
    }
}