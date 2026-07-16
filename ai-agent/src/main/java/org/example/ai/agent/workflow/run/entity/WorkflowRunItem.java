package org.example.ai.agent.workflow.run.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * FOREACH单个项目执行结果。
 */
@Data
@TableName("ai_workflow_run_item")
public class WorkflowRunItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runId;

    private String nodeId;

    private Integer itemIndex;

    private String itemJson;

    private String resultJson;

    private String status;

    private String errorCode;

    private String errorMessage;

    private Long durationMs;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}