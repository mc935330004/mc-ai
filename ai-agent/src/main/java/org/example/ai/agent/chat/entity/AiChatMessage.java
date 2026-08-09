package org.example.ai.agent.chat.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

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
     * 消息类型：
     * TEXT：普通文本回答
     * ACTION_FORM：动态表单
     * ACTION_PREVIEW：写操作确认预览
     */
    private String messageType;

    /**
     *  结构化消息载荷 JSON。
     *
     * ACTION_FORM 保存 ActionFormVO；
     * ACTION_PREVIEW 保存 ActionPreviewVO；
     * TEXT 可保存 ChatTextPayloadVO，用于恢复事实卡片、RAG 引用和工作流结果。
     * 普通文本回答没有结构化数据时传 null。
     */
    private String payloadJson;
    /**
     * 运行ID
     */
    private String runId;
    /**
     * 模型代码
     */
    private String modelCode;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}