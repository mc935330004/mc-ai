package org.example.ai.agent.capability.evaluation.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 能力路由反馈信息
 */
@Data
@TableName("ai_capability_route_feedback")
public class CapabilityRouteFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long routeLogId;

    private String runId;

    private String originalCapabilityCode;

    private Integer correctFlag;

    private String expectedCapabilityCode;

    /**
     * PENDING / APPROVED / REJECTED
     */
    private String feedbackStatus;

    private String comment;

    private String submittedBy;

    private String reviewedBy;

    private LocalDateTime reviewedAt;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}