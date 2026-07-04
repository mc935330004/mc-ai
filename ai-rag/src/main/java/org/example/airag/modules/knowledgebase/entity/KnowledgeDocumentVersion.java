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
 * 制度知识文档版本表。
 */
@Data
@TableName("knowledge_document_version")
public class KnowledgeDocumentVersion implements Serializable {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 文档ID
     */
    private Long documentId;

    /**
     * 版本号，例如 v1.0、v1.1
     */
    private String versionNo;

    /**
     * 原始文件名
     */
    private String originalFilename;

    /**
     * 文件类型
     */
    private String contentType;

    /**
     * 文件大小，单位字节
     */
    private Long fileSize;

    /**
     * 文件哈希，用于去重
     */
    private String fileHash;

    /**
     * 文件存储路径
     */
    private String storagePath;

    /**
     * 解析状态：PENDING、PROCESSING、COMPLETED、FAILED
     */
    private String parseStatus;

    /**
     * 向量化状态：PENDING、PROCESSING、COMPLETED、FAILED
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
     * 生效开始时间
     */
    private LocalDateTime effectiveStartTime;

    /**
     * 生效结束时间
     */
    private LocalDateTime effectiveEndTime;

    /**
     * 发布时间
     */
    private LocalDateTime publishedAt;

    /**
     * 废止时间
     */
    private LocalDateTime deprecatedAt;

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