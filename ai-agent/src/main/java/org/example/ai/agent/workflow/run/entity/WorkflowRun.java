package org.example.ai.agent.workflow.run.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次工作流运行实例。
 */
@Data
@TableName("ai_workflow_run")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String agentRunId;

    private String rootRunId;

    private String sourceRunId;

    private String requestId;

    private Long workflowId;

    private String workflowCode;

    private String workflowName;

    private Long workflowVersionId;

    private Integer workflowVersionNo;

    private Integer configRevision;

    private String configChecksum;

    private String origin;

    private String status;

    private String userId;

    private String inputJson;

    private String resultJson;

    private Integer totalItemCount;

    private Integer successItemCount;

    private Integer failureItemCount;

    private Integer skippedItemCount;

    private String errorCode;

    private String errorMessage;

    private Long durationMs;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime finishedAt;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}