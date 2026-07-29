package org.example.ai.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_chat_message")
public class AiChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID
     */
    private String sessionId;
    /**
     * 用户ID
     */
    private String userId;
    /**
     * 角色
     */
    private String role;
    /**
     * 内容
     */
    private String content;
    /**
     * 运行ID
     */
    private String runId;
    /**
     * 模型代码
     */
    private String modelCode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}