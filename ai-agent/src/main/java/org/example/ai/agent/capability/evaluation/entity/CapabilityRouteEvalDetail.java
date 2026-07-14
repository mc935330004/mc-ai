package org.example.ai.agent.capability.evaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_capability_route_eval_detail")
public class CapabilityRouteEvalDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String evalRunId;

    private Long caseId;

    private String userQuestion;

    private String expectedRouteType;

    private String actualRouteType;

    private String expectedCapabilityCode;

    private String actualCapabilityCode;

    private Integer expectedClarify;

    private Integer actualClarify;

    private String expectedInputJson;

    private String actualInputJson;

    private Integer passed;

    private String failureReason;

    private Long durationMs;

    private LocalDateTime createdAt;
}