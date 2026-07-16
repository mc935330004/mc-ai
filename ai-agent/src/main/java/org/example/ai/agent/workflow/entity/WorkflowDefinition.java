package org.example.ai.agent.workflow.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI工作流定义。
 *
 * 当前表保存的是可编辑草稿。
 * Agent正式运行不能直接读取graphSpecJson，
 * 必须读取activeVersionId对应的发布版本。
 */
@Data
@TableName("ai_workflow_definition")
public class WorkflowDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 稳定工作流编码。
     *
     * 创建后禁止修改，因为Agent、版本和运行记录都会引用它。
     */
    private String workflowCode;

    private String workflowName;

    private String description;

    /**
     * 当前可编辑GraphSpec草稿。
     */
    private String graphSpecJson;

    /**
     * 草稿修订号。
     */
    private Integer configRevision;

    /**
     * 当前草稿校验和。
     */
    private String draftChecksum;

    /**
     * 当前正式发布版本校验和。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String configChecksum;

    /**
     * 当前正式运行版本ID。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long activeVersionId;

    /**
     * DRAFT、PUBLISHED、DISABLED。
     */
    private String publishStatus;

    private Integer enabled;

    /**
     * 1表示草稿与发布版本不同。
     */
    private Integer draftDirty;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime validatedAt;

    private String createdBy;

    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}