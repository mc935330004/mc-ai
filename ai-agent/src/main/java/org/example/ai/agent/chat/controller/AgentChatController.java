package org.example.ai.agent.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.dto.ChatSessionCreateDTO;
import org.example.ai.agent.chat.dto.ChatSessionModelDTO;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.example.ai.agent.chat.service.AiChatSessionService;
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
    private final AiChatSessionService aiChatSessionService;
    private final KnowledgeAccessContext knowledgeAccessContext;
    /**
     * 统一流式聊天入口。
     *
     * 后端只提供当前统一协议，
     * 不再根据请求头切换旧版本。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat( @Valid @RequestBody AgentRequest request) {
        // 用户身份只能从服务端认证上下文读取。
        String userId = currentUserProvider.getRequiredUserId();
        request.setUserId(userId);
        // 认证信息只允许由服务端注入。
        request.setAuthorization(currentUserProvider.getRequiredAuthorization());
        /*
         * Agent会在线程池中执行，
         * 必须提前保存可信租户和部门身份。
         */
        request.setKnowledgeAccessPrincipal(knowledgeAccessContext.getCurrentPrincipal());

        // 模型编码必须来自后台已经启用的模型配置。
        String modelCode = aiChatSessionService.resolveModelCode(
                        userId,
                        request.getConversationId(),
                        request.getModelCode()
                );
        request.setModelCode(modelCode);
        // 只注入受控数量的历史上下文。
        request.setConversationMemory(
                aiChatSessionService.buildMemory(
                        userId,
                        request.getConversationId()
                )
        );
        // 先保存用户问题，助手回答完成后再保存回答。
        aiChatSessionService.saveUserMessage(
                userId,
                request.getConversationId(),
                request.getUserQuestion(),
                modelCode
        );
        return agentOrchestrator.chat(request);
    }

    /**
     * 终止指定会话中正在执行的AI回答。
     */
    @PostMapping("/sessions/{conversationId}/runs/{runId}/cancel")
    public Result<Boolean> cancelRun(@PathVariable String conversationId, @PathVariable String runId) {
        String userId = currentUserProvider.getRequiredUserId();
        boolean cancelled = agentOrchestrator.cancel(userId, conversationId, runId);
        return Result.success(cancelled);
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
