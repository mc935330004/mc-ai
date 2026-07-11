package org.example.ai.agent.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.example.ai.agent.security.CurrentUserProvider;
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
    private final CurrentUserProvider currentUserProvider;
    /**
     * 流式聊天入口。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody AgentRequest request) {
        // 忽略请求体中的 userId，只使用服务端解析出的登录用户
        request.setUserId(currentUserProvider.getRequiredUserId());
        // 用户身份与认证信息只能由服务端从请求头读取
        request.setAuthorization(currentUserProvider.getRequiredAuthorization());
        return agentOrchestrator.chat(request);
    }
}
