package org.example.ai.agent.capability.evaluation.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 能力路由决策日志。
 */
@Data
@TableName("ai_capability_route_log")
public class CapabilityRouteLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String userQuestion;

    /**
     * SELECTED / CLARIFY / NO_CANDIDATE / FAILED
     */
    private String decisionStatus;

    private String selectedCapabilityCode;

    private Double confidence;

    private String candidatesJson;

    private String reason;

    private String clarifyQuestion;

    private Long durationMs;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}