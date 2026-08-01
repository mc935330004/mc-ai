package org.example.ai.agent.chat.service;

import org.example.ai.agent.chat.dto.ChatSessionCreateDTO;
import org.example.ai.agent.chat.vo.ChatMessageVO;
import org.example.ai.agent.chat.vo.ChatModelVO;
import org.example.ai.agent.chat.vo.ChatSessionVO;

import java.util.List;

public interface AiChatSessionService {

    /**
     * 中文注释：返回 yml 中启用的模型列表。
     */
    List<ChatModelVO> listModels();

    /**
     * 中文注释：查询当前用户的会话列表。
     */
    List<ChatSessionVO> listSessions(String userId);

    /**
     * 中文注释：创建新会话。
     */
    ChatSessionVO createSession(String userId, ChatSessionCreateDTO dto);

    /**
     * 中文注释：切换会话使用的模型。
     */
    void updateSessionModel(String userId, String sessionId, String modelCode);

    /**
     * 中文注释：逻辑删除会话。
     */
    void deleteSession(String userId, String sessionId);

    /**
     * 中文注释：查询会话历史消息。
     */
    List<ChatMessageVO> listMessages(String userId, String sessionId);

    /**
     * 中文注释：解析最终使用的模型编码。
     */
    String resolveModelCode(String userId, String sessionId, String modelCode);

    /**
     * 中文注释：构建最近历史对话记忆。
     */
    String buildMemory(String userId, String sessionId);

    /**
     * 中文注释：保存用户消息。
     */
    void saveUserMessage(String userId, String sessionId, String content, String modelCode);

    /**
     * 中文注释：保存 AI 助手消息。
     *
     * @param messageType 消息类型：TEXT、ACTION_FORM、ACTION_PREVIEW
     * @param payloadJson 结构化载荷 JSON；TEXT 可保存 ChatTextPayloadVO，
     *                    没有结构化展示数据时传 null
     */
    void saveAssistantMessage(
            String userId,
            String sessionId,
            String content,
            String runId,
            String modelCode,
            String messageType,
            String payloadJson
    );
}