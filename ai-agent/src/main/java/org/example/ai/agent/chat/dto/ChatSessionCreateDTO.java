package org.example.ai.agent.chat.dto;

import lombok.Data;

@Data
public class ChatSessionCreateDTO {

    /**
     * 中文注释：新会话标题，不传时后端默认“新对话”。
     */
    private String title;

    /**
     * 中文注释：新会话默认使用的模型编码。
     */
    private String modelCode;
}