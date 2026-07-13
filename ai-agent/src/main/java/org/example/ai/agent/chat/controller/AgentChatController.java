package org.example.ai.agent.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    private final AgentOrchestrator agentOrchestrator;
    private final CurrentUserProvider currentUserProvider;
    /**
     * 流式聊天入口。
     * 1：兼容旧前端。
     * 2：使用新版增量SSE协议。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody AgentRequest request,
                                 @RequestHeader(value = "X-Agent-Stream-Version",required = false ) Integer streamVersion) {
        // 忽略请求体中的 userId，只使用服务端解析出的登录用户
        request.setUserId(currentUserProvider.getRequiredUserId());
        // 用户身份与认证信息只能由服务端从请求头读取
        request.setAuthorization(currentUserProvider.getRequiredAuthorization());
        /*
         * 协议版本由请求头控制。
         * 只允许1和2，非法值回退到默认配置。
         */
        request.setStreamVersion(streamVersion);
        return agentOrchestrator.chat(request);
    }
}
