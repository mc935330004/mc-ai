package org.example.ai.agent.chat.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatMessageVO {

    /**
     * 中文注释：消息ID。
     */
    private Long id;

    /**
     * 中文注释：消息角色，USER 或 ASSISTANT。
     */
    private String role;

    /**
     * 中文注释：消息正文。
     */
    private String content;

    /**
     * 中文注释：AI回答对应的运行ID。
     */
    private String runId;

    /**
     * 中文注释：本条消息使用的模型编码。
     */
    private String modelCode;

    /**
     * 中文注释：创建时间。
     */
    private LocalDateTime createdAt;
    /**
     * 消息类型：TEXT、ACTION_FORM、ACTION_PREVIEW。
     */
    private String messageType;

    /**
     * 结构化消息JSON快照。
     *
     * 前端根据 messageType 将该字段恢复成动态表单或操作预览。
     */
    private String payloadJson;
}