package org.example.ai.agent.business;

import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.support.AgentStreamSession;

/**
 * 对话式业务助手的单一编排入口。
 */
public interface BusinessAssistantService {

    /**
     * 处理已经由总编排器确认的只读业务查询。
     */
    void handle(AgentRequest request, AgentStreamSession stream, String agentRunId);
}
