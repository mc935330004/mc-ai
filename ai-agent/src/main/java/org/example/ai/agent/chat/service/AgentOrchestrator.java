package org.example.ai.agent.chat.service;

import org.example.ai.agent.chat.entity.AgentRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 *
 * 聊天 Orchestrator
 */

public interface AgentOrchestrator {

    SseEmitter chat(AgentRequest request);
}