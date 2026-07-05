package org.example.ai.agent.modules.KnowledgeLog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("knowledge_query_reference")
public class KnowledgeQueryReference {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 问答日志ID
     */
    @TableField("query_log_id")
    private Long queryLogId;

    /**
     * 文档ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 版本ID
     */
    @TableField("version_id")
    private Long versionId;

    /**
     * 切片ID
     */
    @TableField("chunk_id")
    private Long chunkId;

    /**
     * 切片序号
     */
    @TableField("chunk_index")
    private Integer chunkIndex;

    /**
     * 来源文件名
     */
    @TableField("source")
    private String source;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}