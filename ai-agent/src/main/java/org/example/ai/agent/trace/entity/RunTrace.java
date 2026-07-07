package org.example.ai.agent.trace.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 运行主记录实体。
 *
 * 对应 ai_run_trace 表。
 * 一次用户提问对应一条 RunTrace。
 */
@Data
@TableName("ai_run_trace")
public class RunTrace {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 本次 Agent 运行 ID。
     *
     * 用于串联 ai_run_trace 和 ai_run_step。
     */
    private String runId;

    /**
     * 会话 ID。
     */
    private String conversationId;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 用户原始问题。
     */
    private String question;

    /**
     * 路由类型。
     *
     * 示例：
     * RAG_ONLY、BUSINESS_QUERY、MIXED_QUERY。
     */
    private String routeType;

    /**
     * 运行状态。
     *
     * 示例：
     * RUNNING、SUCCESS、FAILED。
     */
    private String status;

    /**
     * 错误信息。
     *
     * 只有失败时才写入。
     */
    private String errorMessage;

    /**
     * 总耗时，单位毫秒。
     */
    private Long totalDurationMs;

    /**
     * 创建时间。
     *
     * 可以交给数据库默认值处理。
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    /**
     * 更新时间。
     *
     * 可以交给数据库 ON UPDATE 处理。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}