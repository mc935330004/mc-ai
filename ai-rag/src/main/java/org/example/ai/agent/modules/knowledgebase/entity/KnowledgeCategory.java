package org.example.ai.agent.modules.knowledgebase.entity;

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
 * 知识分类表。
 */
@Data
@TableName("knowledge_category")
public class KnowledgeCategory implements Serializable {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 父级分类ID，0表示根分类
     */
    private Long parentId;

    /**
     * 分类名称，例如HR制度、财务流程、采购规范
     */
    private String name;

    /**
     * 分类编码，便于前端和权限系统识别
     */
    private String code;

    /**
     * 排序值，越小越靠前
     */
    private Integer sortOrder;

    /**
     * 是否启用：0-禁用，1-启用
     */
    private Boolean enabled;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除：0-未删除，1-已删除
     */
    private Integer delFlag;
}
