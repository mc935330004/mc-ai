package org.example.ai.agent.workflow.answer.artifact.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 工作流安全结果快照。
 *
 * 这里只保存经过字段可见性策略过滤后的结果，
 * 禁止保存业务系统原始响应和认证信息。
 */
@Data
@TableName("ai_result_artifact")
public class ResultArtifact {

    @TableId(type = IdType.INPUT)
    private String id;

    private String runId;
    private String sessionId;
    private String userId;

    private String workflowCode;
    private String workflowName;
    private Long workflowVersionId;

    private String status;
    private Boolean partialSuccess;
    private Boolean dataComplete;

    private Long topLevelTotalCount;
    private Long topLevelSuccessCount;
    private Long topLevelFailureCount;
    private Long topLevelSkippedCount;

    private Long descendantTotalCount;
    private Long descendantSuccessCount;
    private Long descendantFailureCount;
    private Long descendantSkippedCount;

    private Integer plannedChunkCount;
    private Integer storedChunkCount;
    private Integer sourceCharCount;
    private Integer chunkCharCount;

    private String payloadChecksum;
    private String fieldSemanticsJson;

    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;
}