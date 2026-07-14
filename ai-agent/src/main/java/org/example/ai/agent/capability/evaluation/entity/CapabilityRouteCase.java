package org.example.ai.agent.capability.evaluation.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Data
@TableName("ai_capability_route_case")
public class CapabilityRouteCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userQuestion;

    private String expectedRouteType;

    private String expectedCapabilityCode;

    private Integer shouldClarify;

    private String expectedInputJson;

    /**
     * MANUAL / FEEDBACK
     */
    private String sourceType;

    private Long sourceFeedbackId;

    private String tags;

    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}