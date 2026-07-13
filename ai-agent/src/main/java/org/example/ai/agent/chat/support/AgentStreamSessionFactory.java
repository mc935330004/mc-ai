package org.example.ai.agent.chat.support;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.AgentStreamProperties;
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
                        markdownChunker );
        /*
         * 超时时主动发送ERROR并关闭连接。
         */
        emitter.onTimeout(session::timeout);
        return session;
    }
}