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
import org.example.ai.agent.chat.vo.ChatMessageVO;
import org.example.ai.agent.chat.vo.ChatModelVO;
import org.example.ai.agent.chat.vo.ChatSessionVO;
import org.example.ai.agent.common.config.AgentModelProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AiChatSessionServiceImpl implements AiChatSessionService {

    /**
     * 中文注释：最多取最近10条历史，避免提示词过长。
     */
    private static final int MEMORY_MESSAGE_LIMIT = 10;

    private final AiChatSessionMapper sessionMapper;
    private final AiChatMessageMapper messageMapper;
    private final AgentModelProperties modelProperties;
    private static final String MESSAGE_TYPE_TEXT = "TEXT";
    @Override
    public List<ChatModelVO> listModels() {
        return modelProperties.getModels().stream()
                .filter(AgentModelProperties.ModelItem::isEnabled)
                .map(item -> ChatModelVO.builder()
                        .code(item.getCode())
                        .name(item.getName())
                        .provider(item.getProvider())
                        .defaultModel(item.getCode().equals(modelProperties.getDefaultCode()))
                        .build())
                .toList();
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
        String modelCode = modelProperties.resolve(dto == null ? null : dto.getModelCode()).getCode();

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
        // 中文注释：所有会话操作必须先验证当前用户的会话归属。
        requireSession(userId, sessionId);
        String resolvedModelCode = modelProperties.resolve(modelCode).getCode();

        sessionMapper.update(null, new LambdaUpdateWrapper<AiChatSession>()
                .eq(AiChatSession::getId, sessionId)
                .eq(AiChatSession::getUserId, userId)
                .eq(AiChatSession::getDeleted, 0)
                .set(AiChatSession::getModelCode, resolvedModelCode));
    }

    @Override
    public void deleteSession(String userId, String sessionId) {
        // 中文注释：所有会话操作必须先验证当前用户的会话归属。
        requireSession(userId, sessionId);
        sessionMapper.update(null, new LambdaUpdateWrapper<AiChatSession>()
                .eq(AiChatSession::getId, sessionId)
                .eq(AiChatSession::getUserId, userId)
                .set(AiChatSession::getDeleted, 1));
    }

    @Override
    public List<ChatMessageVO> listMessages(String userId, String sessionId) {
        // 中文注释：所有会话操作必须先验证当前用户的会话归属。
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
    public String resolveModelCode(String userId, String sessionId, String modelCode) {
        // 中文注释：必须先验证会话归属，不能因为前端传了模型就跳过。
        AiChatSession session = requireSession(userId, sessionId);

        return modelProperties.resolve(
                StringUtils.hasText(modelCode)
                        ? modelCode
                        : session.getModelCode()).getCode();
    }

    @Override
    public String buildMemory(String userId, String sessionId) {
        // 中文注释：所有会话操作必须先验证当前用户的会话归属。
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
        saveMessage(
                userId,
                sessionId,
                "ASSISTANT",
                content,
                runId,
                modelCode,
                messageType,
                payloadJson
        );
    }

    private void saveMessage( String userId,
            String sessionId,
            String role,
            String content,
            String runId,
            String modelCode,
            String messageType,
            String payloadJson) {

        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(content)) {
            return;
        }

        // 中文注释：保存前验证会话属于当前登录用户。
        requireSession(userId, sessionId);

        AiChatMessage message = new AiChatMessage();
        message.setSessionId(sessionId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setMessageType( StringUtils.hasText(messageType)
                        ? messageType
                        : MESSAGE_TYPE_TEXT );
        message.setPayloadJson(payloadJson);
        message.setRunId(runId);
        message.setModelCode(modelCode);
        message.setCreatedAt(LocalDateTime.now());

        messageMapper.insert(message);
        int updated = sessionMapper.update(
                null,
                new LambdaUpdateWrapper<AiChatSession>()
                        .eq(AiChatSession::getId, sessionId)
                        .eq(AiChatSession::getUserId, userId)
                        .eq(AiChatSession::getDeleted, 0)
                        .set(
                                AiChatSession::getLastMessage,
                                content.length() > 200
                                        ? content.substring(0, 200)
                                        : content
                        )
                        .setSql("message_count = message_count + 1")
        );

        if (updated != 1) {
            // 中文注释：事务会同时回滚已经插入的聊天消息。
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "会话已删除或无权访问"
            );
        }
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
     * 中文注释：校验会话存在、未删除且属于当前登录用户。
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
                        .last("LIMIT 1")
        );

        if (session == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "会话不存在或无权访问"
            );
        }
        return session;
    }
}