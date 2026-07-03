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
 * 知识切片明细表。
 */
@Data
@TableName("knowledge_chunk")
public class KnowledgeChunk implements Serializable {

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
     * 文档版本ID
     */
    private Long versionId;

    /**
     * 切片序号，从0开始
     */
    private Integer chunkIndex;

    /**
     * 切片文本内容
     */
    private String content;

    /**
     * 切片内容哈希
     */
    private String contentHash;

    /**
     * 切片Token数量
     */
    private Integer tokenCount;

    /**
     * 页码，无法识别时为空
     */
    private Integer pageNumber;

    /**
     * 是否参与检索：0-否，1-是
     */
    private Integer enabled;

    /**
     * 向量库中的向量ID或业务标识
     */
    private String vectorId;

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

    /**
     * 源文件名称
     */
    @TableField(exist = false)
    private String originalFilename;

    /**
     * 源文件版本号
      */
    @TableField(exist = false)
    private String versionNo;

    /**
     * 文档状态
     */
    @TableField(exist = false)
    private String documentStatus;
}
