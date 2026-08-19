package org.example.ai.agent.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.dto.ChatSessionCreateDTO;
import org.example.ai.agent.chat.dto.ChatSessionModelDTO;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.support.AgentStreamVersionResolver;
import org.example.ai.agent.chat.vo.ChatMessageVO;
import org.example.ai.agent.chat.vo.ChatModelVO;
import org.example.ai.agent.chat.vo.ChatSessionVO;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    private final AgentOrchestrator agentOrchestrator;
    private final CurrentUserProvider currentUserProvider;
    private final AgentStreamVersionResolver streamVersionResolver;
    private final AiChatSessionService aiChatSessionService;
    private final KnowledgeAccessContext knowledgeAccessContext;
    /**
     * 流式聊天入口。
     * 1：兼容旧前端。
     * 2：使用新版增量SSE协议。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody AgentRequest request,
                                 @RequestHeader(value = "X-Agent-Stream-Version",required = false ) Integer streamVersion) {
        // 忽略请求体中的 userId，只使用服务端解析出的登录用户
        // 用户身份只能从服务端认证上下文读取。
        String userId = currentUserProvider.getRequiredUserId();
        request.setUserId(userId);
        // 用户身份与认证信息只能由服务端从请求头读取
        request.setAuthorization(currentUserProvider.getRequiredAuthorization());
        /*
         * Agent主体会在线程池中异步执行，
         * 必须在当前请求线程提前捕获可信租户和部门身份。
         */
        request.setKnowledgeAccessPrincipal( knowledgeAccessContext.getCurrentPrincipal());
       //  模型编码由后端严格校验，未配置或已停用时拒绝请求。
        String modelCode = aiChatSessionService.resolveModelCode(userId, request.getConversationId(), request.getModelCode());
        request.setModelCode(modelCode);
        //  只取最近少量历史，避免提示词无限增长。
        request.setConversationMemory(aiChatSessionService.buildMemory(userId, request.getConversationId()));
        //  先保存用户问题，AI回答完成后再保存助手回答。
        aiChatSessionService.saveUserMessage(userId, request.getConversationId(), request.getUserQuestion(), modelCode);

        int resolvedVersion = streamVersionResolver.resolve( streamVersion,userId );

        request.setStreamVersion(resolvedVersion);
        return agentOrchestrator.chat(request);
    }

    @GetMapping("/models")
    public Result<List<ChatModelVO>> listModels() {
        String userId =currentUserProvider.getRequiredUserId();
        return Result.success(aiChatSessionService.listModels(userId));
    }

    @GetMapping("/sessions")
    public Result<List<ChatSessionVO>> listSessions() {
        String userId = currentUserProvider.getRequiredUserId();
        return Result.success(aiChatSessionService.listSessions(userId));
    }

    @PostMapping("/sessions")
    public Result<ChatSessionVO> createSession(@RequestBody(required = false) ChatSessionCreateDTO dto) {
        String userId = currentUserProvider.getRequiredUserId();
        return Result.success(aiChatSessionService.createSession(userId, dto));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ChatMessageVO>> listMessages(@PathVariable String sessionId) {
        String userId = currentUserProvider.getRequiredUserId();
        return Result.success(aiChatSessionService.listMessages(userId, sessionId));
    }

    @PatchMapping("/sessions/{sessionId}/model")
    public Result<Void> updateModel(@PathVariable String sessionId,
                                    @Valid @RequestBody ChatSessionModelDTO dto) {
        String userId = currentUserProvider.getRequiredUserId();
        aiChatSessionService.updateSessionModel(userId, sessionId, dto.getModelCode());
        return Result.success();
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        String userId = currentUserProvider.getRequiredUserId();
        aiChatSessionService.deleteSession(userId, sessionId);
        return Result.success();
    }
}
