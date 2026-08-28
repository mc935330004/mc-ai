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
import org.example.ai.agent.chat.memory.model.ResultStatisticsContext;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 *  使用一条 JSON 状态记录保存每个会话的最新业务上下文。
 */
@Service
@RequiredArgsConstructor
public class ConversationStateServiceImpl  implements ConversationStateService {

    private final AiConversationStateMapper stateMapper;
    private final AiChatSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;

    /**
     *  读取状态前校验会话归属，防止跨用户访问。
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
     *  首次调用插入状态，后续调用通过版本号安全更新状态。
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

        //  更新数量为零表示状态已被其他请求修改。
        if (stateMapper.updateById(entity) != 1) {
            throw new BusinessException(
                    409,
                    "会话状态已发生变化，请重新发送"
            );
        }
    }

    /**
     *  使用用户ID和会话ID共同删除，避免清理其他用户的数据。
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
     * 在当前会话状态上更新统计记忆。
     *
     * 快照身份、上一轮统计身份和数据库版本共同防止并发覆盖。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveStatisticsContext(String userId, String sessionId, ResultStatisticsContext context, String expectedPreviousRunId) {

        requireSession(userId, sessionId);

        if (context == null || context.fieldIds().isEmpty() || !StringUtils.hasText(context.runId())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "统计上下文不完整"
            );
        }

        AiConversationState entity = findState(userId, sessionId);
        if (entity == null) {
            return false;
        }

        BusinessConversationState state;
        try {
            state = objectMapper.readValue(entity.getStateJson(), BusinessConversationState.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "会话业务状态解析失败",
                    exception
            );
        }

        // 用户已经重新查询或切换业务结果时，不写回旧统计。
        if (state == null || !context.matchesArtifact(state.getResultArtifactId())) {
            return false;
        }

        ResultStatisticsContext previous = state.getLastStatisticsContext();
        String actualPreviousRunId = null;

        if (previous != null && previous.matchesArtifact(state.getResultArtifactId())) {
            actualPreviousRunId = previous.runId();
        }

        // 同一快照已经完成了另一轮统计时，不覆盖它的字段记忆。
        if (!Objects.equals(expectedPreviousRunId, actualPreviousRunId)) {
            return false;
        }
        state.setLastStatisticsContext(context);
        state.setLastPresentationMode("CHAT");
        state.setUpdatedAt(LocalDateTime.now());
        entity.setStateJson(writeState(state));
        // 使用本次读取的版本更新；冲突时不重试覆盖。
        return stateMapper.updateById(entity) == 1;
    }

    /**
     *  查询指定用户和会话的唯一状态记录。
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
     *  将结构化状态序列化成数据库 JSON。
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
     *  校验会话存在、未删除且属于当前用户。
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
     *  统一校验状态操作所需的基础参数。
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