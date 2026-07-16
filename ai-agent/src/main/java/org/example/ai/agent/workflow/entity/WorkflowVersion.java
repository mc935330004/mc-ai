package org.example.ai.agent.workflow.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工作流不可变发布版本。
 *
 * snapshotJson一旦插入禁止修改。
 * 后续只允许将status从ACTIVE改为RETIRED。
 */
@Data
@TableName("ai_workflow_version")
public class WorkflowVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private String workflowCode;

    private Integer versionNo;

    private Integer configRevision;

    /**
     * 规范化后的不可变GraphSpec。
     */
    private String snapshotJson;

    private String configChecksum;

    private Integer nodeCount;

    private Integer edgeCount;

    /**
     * ACTIVE或RETIRED。
     */
    private String status;

    private String publishedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime publishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime retiredAt;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}