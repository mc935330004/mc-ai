package org.example.ai.agent.capability.evaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ai_capability_route_eval_run")
public class CapabilityRouteEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String evalRunId;

    private String status;

    private Integer totalCount;

    private Integer passedCount;

    private Integer failedCount;

    private BigDecimal accuracy;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}