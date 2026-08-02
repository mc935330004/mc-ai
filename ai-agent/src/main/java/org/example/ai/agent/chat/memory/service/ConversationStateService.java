package org.example.ai.agent.chat.memory.service;

import org.example.ai.agent.chat.memory.model.BusinessConversationState;

import java.util.Optional;

/**
 * 中文注释：统一管理会话的结构化业务状态。
 */
public interface ConversationStateService {

    /**
     * 中文注释：读取当前用户指定会话的业务状态。
     */
    Optional<BusinessConversationState> loadState(String userId,String sessionId);

    /**
     * 中文注释：新增或覆盖当前会话的业务状态。
     */
    void saveState(
            String userId,
            String sessionId,
            BusinessConversationState state
    );

    /**
     * 中文注释：删除会话时同步清理业务状态。
     */
    void clearState(
            String userId,
            String sessionId
    );
}