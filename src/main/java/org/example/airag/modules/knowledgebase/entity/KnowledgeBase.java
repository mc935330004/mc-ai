package org.example.airag.modules.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文件表
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase implements Serializable {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 知识库文件名称
     */
    private String name;

    /**
     * 分类，例如：项目文档、合同、需求文档、技术文档
     */
    private String category;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 文件类型，例如：application/pdf、text/plain
     */
    private String contentType;

    /**
     * 文件大小，单位字节
     */
    private Long fileSize;

    /**
     * 文件hash，用于判断文件是否重复
     */
    private String fileHash;

    /**
     * 文件存储路径
     */
    private String storagePath;

    /**
     * 向量化状态：PENDING待处理，PROCESSING处理中，COMPLETED成功，FAILED失败
     */
    private String vectorStatus;

    /**
     * 向量化失败原因
     */
    private String vectorError;

    /**
     * 切片数量
     */
    private Integer chunkCount;

    /**
     * 问题数量
     */
    private Long questionCount;

    /**
     * 访问次数
     */
    private Long accessCount;

    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessedAt;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 删除标识 0 否 1 是
     */
    private Integer delFlag;
}