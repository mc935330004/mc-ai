package org.example.ai.agent.chat.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionVO {

    /**
     * 中文注释：会话ID，前端作为 conversationId 使用。
     */
    private String id;

    /**
     * 中文注释：会话标题。
     */
    private String title;

    /**
     * 中文注释：当前会话绑定的模型编码。
     */
    private String modelCode;

    /**
     * 中文注释：最后一条消息摘要。
     */
    private String lastMessage;

    /**
     * 中文注释：会话消息数量。
     */
    private Integer messageCount;

    /**
     * 中文注释：更新时间，用于前端排序展示。
     */
    private LocalDateTime updatedAt;
}