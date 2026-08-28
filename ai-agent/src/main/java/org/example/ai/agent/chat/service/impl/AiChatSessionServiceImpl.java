package org.example.ai.agent.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.dto.ChatSessionCreateDTO;
import org.example.ai.agent.chat.entity.AiChatMessage;
import org.example.ai.agent.chat.entity.AiChatSession;
import org.example.ai.agent.chat.mapper.AiChatMessageMapper;
import org.example.ai.agent.chat.mapper.AiChatSessionMapper;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.stream.ResponseChecksumService;
import org.example.ai.agent.chat.support.ActiveAgentRunRegistry;
import org.example.ai.agent.chat.vo.ChatMessageVO;
import org.example.ai.agent.chat.vo.ChatModelVO;
import org.example.ai.agent.chat.vo.ChatResponseSnapshotVO;
import org.example.ai.agent.chat.vo.ChatSessionVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modelconfig.service.ChatModelPolicyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
@Service
@RequiredArgsConstructor
public class AiChatSessionServiceImpl implements AiChatSessionService {

    /**
     *  最多取最近10条历史，避免提示词过长。
     */
    private static final int MEMORY_MESSAGE_LIMIT = 10;

    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final ObjectMapper objectMapper;
    /**
     * 统一处理人员授权、默认模型和会话选模。
     */
    private final ChatModelPolicyService chatModelPolicyService;
    /**
     *  管理聊天会话对应的结构化业务状态。
     */
    private final ConversationStateService conversationStateService;
    private static final String MESSAGE_TYPE_TEXT = "TEXT";
    private final ActiveAgentRunRegistry activeAgentRunRegistry;
    private final ResponseChecksumService responseChecksumService;


    @Override
    public List<ChatModelVO> listModels(String userId) {
        return chatModelPolicyService.listSelectableModels(userId);
    }

    @Override
    public List<ChatSessionVO> listSessions(String userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<AiChatSession>()
                        .eq(AiChatSession::getUserId, userId)
                        .eq(AiChatSession::getDeleted, 0)
                        .orderByDesc(AiChatSession::getUpdatedAt))
                .stream()
                .map(this::toSessionVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSessionVO createSession(String userId, ChatSessionCreateDTO dto) {
        String modelCode = chatModelPolicyService.resolveModelCode(userId,dto == null ? null : dto.getModelCode(),null);

        AiChatSession session = new AiChatSession();
        session.setId(UUID.randomUUID().toString().replace("-", ""));
        session.setUserId(userId);
        session.setTitle(StringUtils.hasText(dto == null ? null : dto.getTitle()) ? dto.getTitle() : "新对话");
        session.setModelCode(modelCode);
        session.setLastMessage("");
        session.setMessageCount(0);
        session.setCreatedAt(LocalDateTime.now());
        session.setDeleted(0);

        sessionMapper.insert(session);
        return toSessionVO(session);
    }

    @Override
    public void updateSessionModel(String userId, String sessionId, String modelCode) {
        //  所有会话操作必须先验证当前用户的会话归属。
        requireSession(userId, sessionId);
        String resolvedModelCode =chatModelPolicyService.resolveModelCode(
                        userId,
                        modelCode,
                        null
                );

        sessionMapper.update(null, new LambdaUpdateWrapper<AiChatSession>()
                .eq(AiChatSession::getId, sessionId)
                .eq(AiChatSession::getUserId, userId)
                .eq(AiChatSession::getDeleted, 0)
                .set(AiChatSession::getModelCode, resolvedModelCode));
    }

    /**
     *  删除会话和清理上下文必须位于同一个事务中。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession( String userId,String sessionId) {
        //  清理前必须验证会话存在且属于当前用户。
        requireSession(userId, sessionId);
        /*
         *  必须在会话逻辑删除前清理状态。
         * 状态服务读取会话时要求会话仍处于未删除状态。
         */
        conversationStateService.clearState(
                userId,
                sessionId
        );

        int updated = sessionMapper.update(null,new LambdaUpdateWrapper<AiChatSession>()
                        .eq(AiChatSession::getId, sessionId)
                        .eq(AiChatSession::getUserId, userId)
                        .eq(AiChatSession::getDeleted, 0)
                        .set(AiChatSession::getDeleted, 1));
        if (updated != 1) {
            throw new BusinessException(
                    409,
                    "会话状态已发生变化，请刷新后重试"
            );
        }
    }

    /**
     * 按用户、会话、运行和回答标识恢复快照。
     * 不按最新一条消息猜测，不重新调用工作流或大模型。
     */
    @Override
    public ChatResponseSnapshotVO getResponseSnapshot(String userId, String sessionId, String runId, String responseId) {
        requireSession(userId, sessionId);
        if (!StringUtils.hasText(runId)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "运行ID不能为空"
            );
        }

        String runKey = runId.trim();
        String responseKey = StringUtils.hasText(responseId) ? responseId.trim() : null;

        /*
         * 先读取活动状态，再读取数据库。
         * 避免先查不到快照、随后任务保存并移除登记，
         * 最后误判为没有可恢复结果。
         */
        boolean active = activeAgentRunRegistry.isActive(runKey, userId, sessionId);

        List<AiChatMessage> candidates = messageMapper.selectList(new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getUserId, userId)
                        .eq(AiChatMessage::getSessionId, sessionId)
                        .eq(AiChatMessage::getRunId, runKey)
                        .eq(AiChatMessage::getRole, "ASSISTANT")
                        .eq(AiChatMessage::getMessageType, MESSAGE_TYPE_TEXT)
                        .isNotNull(AiChatMessage::getPayloadJson)
        );

        String documentJson = null;
        JsonNode document = null;
        for (AiChatMessage message : candidates) {
            JsonNode payload = readResponsePayload(message.getPayloadJson());
            // 数据库查询条件和快照内部身份必须一致。
            if (!sessionId.equals(payload.path("conversationId").asText())
                    || !runKey.equals(payload.path("runId").asText())) {
                throw new BusinessException(
                        409,
                        "回答快照与当前会话或运行记录不一致"
                );
            }

            if (responseKey != null && !responseKey.equals(payload.path("responseId").asText())) {
                continue;
            }

            // 定位不唯一时明确报错，不能随意取第一条或最后一条。
            if (document != null) {
                throw new BusinessException(409, "同一次回答存在多份快照，无法安全恢复");
            }
            document = payload;
            documentJson = message.getPayloadJson();
        }
        String state;
        if (document != null && !"RUNNING".equals(document.path("status").asText())) {
            // 数据库最终状态优先，活动任务可能还在执行收尾。
            state = "READY";
        } else {
            state = active ? "PENDING" : "UNAVAILABLE";
        }

        String checksum = documentJson == null ? null : responseChecksumService.calculate(documentJson);
        return new ChatResponseSnapshotVO(state, documentJson, checksum);
    }

    @Override
    public List<ChatMessageVO> listMessages(String userId, String sessionId) {
        //  所有会话操作必须先验证当前用户的会话归属。
        requireSession(userId, sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getUserId, userId)
                        .eq(AiChatMessage::getSessionId, sessionId)
                        .orderByAsc(AiChatMessage::getCreatedAt))
                .stream()
                .map(this::toMessageVO)
                .toList();
    }

    @Override
    public String resolveModelCode(
            String userId,
            String sessionId,
            String modelCode) {

        /*
         * 必须先验证会话归属，
         * 前端传入模型不能绕过会话权限检查。
         */
        AiChatSession session =
                requireSession(userId, sessionId);

        return chatModelPolicyService.resolveModelCode(
                userId,
                modelCode,
                session.getModelCode()
        );
    }

    @Override
    public String buildMemory(String userId, String sessionId) {
        //  所有会话操作必须先验证当前用户的会话归属。
        requireSession(userId, sessionId);
        List<AiChatMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<AiChatMessage>()
                .eq(AiChatMessage::getUserId, userId)
                .eq(AiChatMessage::getSessionId, sessionId)
                .orderByDesc(AiChatMessage::getCreatedAt)
                .last("LIMIT " + MEMORY_MESSAGE_LIMIT));

        Collections.reverse(messages);

        StringBuilder memory = new StringBuilder();
        for (AiChatMessage message : messages) {
            memory.append("USER".equals(message.getRole()) ? "用户：" : "助手：")
                    .append(message.getContent())
                    .append("\n");
        }
        return memory.toString();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUserMessage(
            String userId,
            String sessionId,
            String content,
            String modelCode) {
        saveMessage(
                userId,
                sessionId,
                "USER",
                content,
                null,
                modelCode,
                MESSAGE_TYPE_TEXT,
                null
        );
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveAssistantMessage(
            String userId,
            String sessionId,
            String content,
            String runId,
            String modelCode, String messageType,String payloadJson) {
        saveMessage(userId,sessionId,"ASSISTANT",content,runId, modelCode,messageType,payloadJson);
    }

    /**
     * 更新同一报告响应，不影响同次运行中的独立追问。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateAssistantReportMessage(String userId, String sessionId, String runId, String content, String modelCode, String payloadJson) {

        if (!StringUtils.hasText(payloadJson)
                || !"REPORT".equals(readResponsePayload(payloadJson)
                .path("mode").asText())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "报告更新必须提供完整REPORT快照"
            );
        }

        saveMessage(
                userId, sessionId, "ASSISTANT",
                content, runId, modelCode,
                MESSAGE_TYPE_TEXT, payloadJson
        );
    }

    /**
     * 保存聊天消息。
     * 统一回答按响应标识更新，普通消息仍独立插入。
     */
    private void saveMessage(String userId, String sessionId, String role, String content, String runId, String modelCode, String messageType, String payloadJson) {

        if (!StringUtils.hasText(sessionId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话ID不能为空");
        }

        if (!StringUtils.hasText(content) && !StringUtils.hasText(payloadJson)) {
            return;
        }

        String type = StringUtils.hasText(messageType)
                ? messageType : MESSAGE_TYPE_TEXT;
        String visibleContent = content == null ? "" : content;

        JsonNode response = null;
        if ("ASSISTANT".equals(role)
                && MESSAGE_TYPE_TEXT.equals(type)
                && StringUtils.hasText(payloadJson)) {
            response = readResponsePayload(payloadJson);

            if (!Objects.equals(runId, response.path("runId").asText())
                    || !Objects.equals(sessionId,
                    response.path("conversationId").asText())) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "回答快照与当前运行或会话不一致"
                );
            }
        }

        // 所有消息写入先锁定同一会话，避免并发检查后重复插入。
        AiChatSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiChatSession>()
                        .eq(AiChatSession::getId, sessionId)
                        .eq(AiChatSession::getUserId, userId)
                        .eq(AiChatSession::getDeleted, 0)
                        .last("FOR UPDATE")
        );

        if (session == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "会话不存在或无权访问"
            );
        }

        AiChatMessage message = null;

        if (response != null) {
            String responseId = response.path("responseId").asText();

            List<AiChatMessage> candidates = messageMapper.selectList(
                    new LambdaQueryWrapper<AiChatMessage>()
                            .eq(AiChatMessage::getUserId, userId)
                            .eq(AiChatMessage::getSessionId, sessionId)
                            .eq(AiChatMessage::getRole, "ASSISTANT")
                            .eq(AiChatMessage::getMessageType, MESSAGE_TYPE_TEXT)
                            .eq(AiChatMessage::getRunId, runId)
                            .isNotNull(AiChatMessage::getPayloadJson)
            );

            for (AiChatMessage candidate : candidates) {
                JsonNode saved = readResponsePayload(candidate.getPayloadJson());

                if (!responseId.equals(saved.path("responseId").asText())) {
                    continue;
                }

                if (message != null) {
                    throw new BusinessException(
                            ErrorCode.BAD_REQUEST,
                            "同一响应存在重复消息，请先处理重复记录"
                    );
                }

                if (!response.path("mode").asText()
                        .equals(saved.path("mode").asText())) {
                    throw new BusinessException(
                            ErrorCode.BAD_REQUEST,
                            "同一响应不能切换CHAT和REPORT模式"
                    );
                }

                // 最终快照提交后只接受相同内容重放，禁止被另一份终态或生成中快照覆盖。
                if (!"RUNNING".equals(saved.path("status").asText()) && !saved.equals(response)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "该回答已结束，不能覆盖已保存的最终快照");
                }
                message = candidate;
            }
        }

        boolean inserted = message == null;

        if (inserted) {
            message = new AiChatMessage();
            message.setSessionId(sessionId);
            message.setUserId(userId);
            message.setRole(role);
            message.setMessageType(type);
            message.setRunId(runId);
            message.setCreatedAt(LocalDateTime.now());
        }

        String effectiveModelCode = StringUtils.hasText(modelCode)
                ? modelCode : message.getModelCode();

        // 相同快照重复保存时直接返回，不重复计数。
        if (!inserted
                && Objects.equals(message.getContent(), visibleContent)
                && Objects.equals(message.getPayloadJson(), payloadJson)
                && Objects.equals(message.getModelCode(), effectiveModelCode)) {
            return;
        }

        message.setContent(visibleContent);
        message.setPayloadJson(payloadJson);
        message.setModelCode(effectiveModelCode);

        int saved = inserted
                ? messageMapper.insert(message)
                : messageMapper.updateById(message);

        if (saved != 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "聊天消息保存失败");
        }

        // 更新较早的报告时，不能把会话摘要覆盖成旧消息。
        AiChatMessage latest = messageMapper.selectOne(
                new LambdaQueryWrapper<AiChatMessage>()
                        .eq(AiChatMessage::getUserId, userId)
                        .eq(AiChatMessage::getSessionId, sessionId)
                        .orderByDesc(AiChatMessage::getId)
                        .last("LIMIT 1")
        );

        String lastContent = latest == null || latest.getContent() == null
                ? "" : latest.getContent();

        LambdaUpdateWrapper<AiChatSession> update =
                new LambdaUpdateWrapper<AiChatSession>()
                        .eq(AiChatSession::getId, sessionId)
                        .eq(AiChatSession::getUserId, userId)
                        .eq(AiChatSession::getDeleted, 0)
                        .set(AiChatSession::getLastMessage,
                                lastContent.substring(0, Math.min(200, lastContent.length())))
                        .set(AiChatSession::getUpdatedAt, LocalDateTime.now());

        if (inserted) {
            update.setSql("message_count = message_count + 1");
        }

        // 当前会话已加锁；重复摘要可能不产生数据变化，不据此判断保存失败。
        sessionMapper.update(null, update);
    }

    /**
     * 只接受当前统一回答协议，不兼容历史协议。
     */
    private JsonNode readResponsePayload(String payloadJson) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadJson);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "回答快照不是有效JSON"
            );
        }

        if (payload == null
                || !payload.isObject()
                || !List.of("CHAT", "REPORT")
                .contains(payload.path("mode").asText())
                || payload.path("responseId").asText().isBlank()
                || !List.of("RUNNING", "COMPLETED", "PARTIAL", "FAILED", "CANCELLED")
                .contains(payload.path("status").asText())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "回答快照缺少有效的模式、响应标识或状态"
            );
        }

        return payload;
    }

    private ChatSessionVO toSessionVO(AiChatSession session) {
        return ChatSessionVO.builder()
                .id(session.getId())
                .title(session.getTitle())
                .modelCode(session.getModelCode())
                .lastMessage(session.getLastMessage())
                .messageCount(session.getMessageCount())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private ChatMessageVO toMessageVO(AiChatMessage message) {
        return ChatMessageVO.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .messageType(message.getMessageType())
                .payloadJson(message.getPayloadJson())
                .runId(message.getRunId())
                .modelCode(message.getModelCode())
                .createdAt(message.getCreatedAt())
                .build();
    }

    /**
     *  校验会话存在、未删除且属于当前登录用户。
     */
    private AiChatSession requireSession(String userId, String sessionId) throws BusinessException {
        if (!StringUtils.hasText(sessionId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "会话ID不能为空");
        }
        AiChatSession session = sessionMapper.selectOne(
                new LambdaQueryWrapper<AiChatSession>()
                        .eq(AiChatSession::getId, sessionId)
                        .eq(AiChatSession::getUserId, userId)
                        .eq(AiChatSession::getDeleted, 0)
                        .last("LIMIT 1") );

        if (session == null) {
            throw new BusinessException( ErrorCode.BAD_REQUEST,"会话不存在或无权访问");
        }
        return session;
    }
}