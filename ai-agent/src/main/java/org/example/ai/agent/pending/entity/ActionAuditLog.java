package org.example.ai.agent.pending.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * WRITE 操作追加式审计日志。
 *
 * 只记录操作身份和状态变化，不保存：
 * 1. Authorization；
 * 2. Token；
 * 3. Cookie；
 * 4. 完整业务请求参数；
 * 5. 完整业务响应。
 */
@Data
@TableName("ai_action_audit_log")
public class ActionAuditLog {

    private Long id;

    /**
     * Agent运行ID。
     */
    private String runId;

    /**
     * PM系统真实用户标识。
     */
    private String userId;

    /**
     * WRITE能力编码。
     */
    private String capabilityCode;

    /**
     * WRITE能力名称。
     */
    private String capabilityName;

    /**
     * WRITE生命周期事件。
     */
    private String eventType;

    /**
     * 安全事件摘要。
     */
    private String eventDetail;

    /**
     * 事件发生时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}