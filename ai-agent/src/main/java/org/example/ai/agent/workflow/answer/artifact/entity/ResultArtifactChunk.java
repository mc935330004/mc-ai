package org.example.ai.agent.workflow.answer.artifact.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 工作流结果快照的单个合法JSON分块。
 */
@Data
@TableName("ai_result_artifact_chunk")
public class ResultArtifactChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String artifactId;
    private Integer chunkNo;

    private String sourcePointer;
    private Integer startIndex;
    private Integer endIndex;

    private String payloadJson;
    private String payloadSha256;
    private Integer charCount;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}