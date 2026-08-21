package org.example.ai.agent.chat.service;

import org.example.ai.agent.chat.entity.AgentRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Agent聊天编排入口。
 */
public interface AgentOrchestrator {

    SseEmitter chat(AgentRequest request);

    /**
     * 终止当前用户指定会话中的运行任务。
     */
    boolean cancel(String userId, String conversationId, String runId);
}