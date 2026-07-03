package org.example.airag.modules.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 制度知识文档主表。
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument implements Serializable {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属分类ID
     */
    private Long categoryId;

    /**
     * 文档标题，例如差旅报销制度
     */
    private String title;

    /**
     * 制度编号或文档编号
     */
    private String documentCode;

    /**
     * 归属部门，例如人力资源部、财务部
     */
    private String ownerDept;

    /**
     * 状态：DRAFT-草稿，PUBLISHED-已发布，DEPRECATED-已废止，ARCHIVED-已归档
     */
    private String status;

    /**
     * 当前生效版本ID
     */
    private Long currentVersionId;

    /**
     * 文档摘要
     */
    private String summary;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    /**
     * 更新时间 格式yyyy-MM-dd
     */
    @TableField(fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除：0-未删除，1-已删除
     */
    private Integer delFlag;
}
