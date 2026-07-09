package org.example.ai.agent.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    private final AgentOrchestrator agentOrchestrator;

    /**
     * 流式聊天入口。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody AgentRequest request) {
        return agentOrchestrator.chat(request);
    }
}
