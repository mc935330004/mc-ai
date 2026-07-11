package org.example.ai.agent.pending.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 待确认操作实体。
 *
 * 保存大模型已经生成并经后端校验的固定操作计划。
 * 用户确认时必须读取该记录，不能让模型重新生成参数。
 */
@Data
@TableName("ai_pending_action")
public class PendingAction {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 一次聊天运行对应一个待确认操作。
     */
    private String runId;

    /**
     * 发起操作的真实业务用户。
     */
    private String userId;

    /**
     * 操作能力码。
     */
    private String capabilityCode;

    /**
     * 操作能力名称。
     */
    private String capabilityName;

    /**
     * 固定操作参数 JSON。
     */
    private String inputJson;

    /**
     * 操作摘要。
     */
    private String actionSummary;

    /**
     * 使用 PendingActionStatus.name() 保存。
     */
    private String status;

    /**
     * 防止同一个写操作被重复执行。
     */
    private String idempotencyKey;

    /**
     * 过期时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime expireAt;

    /**
     * 确认时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime confirmedAt;

    /**
     * 执行时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime executedAt;

    /**
     * 错误信息。
     */
    private String errorMessage;

    /**
     * 业务系统真实执行结果。
     */
    private String outputJson;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}