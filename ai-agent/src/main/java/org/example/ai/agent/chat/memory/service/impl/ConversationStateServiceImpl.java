package org.example.ai.agent.chat.memory.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AiChatSession;
import org.example.ai.agent.chat.mapper.AiChatSessionMapper;
import org.example.ai.agent.chat.memory.entity.AiConversationState;
import org.example.ai.agent.chat.memory.mapper.AiConversationStateMapper;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 中文注释：使用一条 JSON 状态记录保存每个会话的最新业务上下文。
 */
@Service
@RequiredArgsConstructor
public class ConversationStateServiceImpl  implements ConversationStateService {

    private final AiConversationStateMapper stateMapper;
    private final AiChatSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    /**
     * 中文注释：读取状态前校验会话归属，防止跨用户访问。
     */
    @Override
    public Optional<BusinessConversationState> loadState(
            String userId,
            String sessionId) {
        requireSession(userId, sessionId);
        AiConversationState entity = findState(userId, sessionId);
        if (entity == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(
                    objectMapper.readValue(
                            entity.getStateJson(),
                            BusinessConversationState.class
                    )
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "会话业务状态解析失败",
                    exception
            );
        }
    }

    /**
     * 中文注释：首次调用插入状态，后续调用通过版本号安全更新状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveState(
            String userId,
            String sessionId,
            BusinessConversationState state
    ) {
        requireSession(userId, sessionId);

        if (state == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "会话业务状态不能为空"
            );
        }

        String stateJson = writeState(state);
        AiConversationState entity = findState(userId, sessionId);

        if (entity == null) {
            AiConversationState newEntity = new AiConversationState();
            newEntity.setSessionId(sessionId);
            newEntity.setUserId(userId);
            newEntity.setStateJson(stateJson);
            newEntity.setVersion(0);
            stateMapper.insert(newEntity);
            return;
        }

        entity.setStateJson(stateJson);

        // 中文注释：更新数量为零表示状态已被其他请求修改。
        if (stateMapper.updateById(entity) != 1) {
            throw new BusinessException(
                    409,
                    "会话状态已发生变化，请重新发送"
            );
        }
    }

    /**
     * 中文注释：使用用户ID和会话ID共同删除，避免清理其他用户的数据。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearState(String userId, String sessionId) {
        requireArguments(userId, sessionId);

        stateMapper.delete(new LambdaQueryWrapper<AiConversationState>()
                        .eq(AiConversationState::getSessionId, sessionId)
                        .eq(AiConversationState::getUserId, userId));
    }

    /**
     * 中文注释：查询指定用户和会话的唯一状态记录。
     */
    private AiConversationState findState(
            String userId,
            String sessionId) {
        return stateMapper.selectOne(
                new LambdaQueryWrapper<AiConversationState>()
                        .eq(AiConversationState::getSessionId, sessionId)
                        .eq(AiConversationState::getUserId, userId)
                        .last("LIMIT 1")
        );
    }

    /**
     * 中文注释：将结构化状态序列化成数据库 JSON。
     */
    private String writeState(BusinessConversationState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "会话业务状态序列化失败",
                    exception
            );
        }
    }

    /**
     * 中文注释：校验会话存在、未删除且属于当前用户。
     */
    private void requireSession(String userId, String sessionId) {
        requireArguments(userId, sessionId);
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
    }

    /**
     * 中文注释：统一校验状态操作所需的基础参数。
     */
    private void requireArguments(String userId, String sessionId) {
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "用户ID不能为空"
            );
        }

        if (!StringUtils.hasText(sessionId)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "会话ID不能为空"
            );
        }
    }
}