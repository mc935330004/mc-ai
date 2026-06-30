package org.example.airag.modules.knowledgebase.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库向量化任务表
 */
@Data
@TableName("knowledge_base_vector_task")
public class KnowledgeBaseVectorTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 知识库文件ID，对应 knowledge_base.id
     */
    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    /**
     * 任务类型：VECTORIZE 向量化，RE_VECTORIZE 重新向量化，DELETE_VECTOR 删除向量
     */
    @TableField("task_type")
    private String taskType;

    /**
     * 任务状态：PENDING 待处理，PROCESSING 处理中，SUCCESS 成功，FAILED 失败，CANCELLED 已取消
     */
    @TableField("status")
    private String status;

    /**
     * 当前重试次数
     */
    @TableField("retry_count")
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    @TableField("max_retry_count")
    private Integer maxRetryCount;

    /**
     * 任务锁持有者，用于多实例部署时标识哪个服务实例正在处理该任务
     */
    @TableField("lock_owner")
    private String lockOwner;

    /**
     * 任务加锁时间
     */
    @TableField("locked_at")
    private LocalDateTime lockedAt;

    /**
     * 任务失败错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 任务开始处理时间
     */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /**
     * 任务完成时间
     */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    /**
     * 文档ID。
     *
     * 新的企业文档流程使用该字段关联 knowledge_document。
     * 旧 knowledge_base 流程可以为空。
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 文档版本ID。
     * 新的企业文档流程使用该字段关联 knowledge_document_version。
     * 后续解析、切片、向量化都应以 versionId 为核心。
     */
    @TableField("version_id")
    private Long versionId;
}