// 文件：src/main/java/org/example/airag/modules/knowledgebase/dto/KnowledgeDocumentListItemDTO.java

package org.example.airag.modules.knowledgebase.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业知识文档列表项。
 */
@Data
public class KnowledgeDocumentListItemVO {
    /**
     * 知识文档ID。
     */
    private Long id;

    /**
     * 知识文档分类ID。
     */
    private Long categoryId;

    /**
     * 知识文档分类名称。
     */
    private String categoryName;

    /**
     * 知识文档标题。
     */
    private String title;

    /**
     * 知识文档代码。
     */
    private String documentCode;

    /**
     * 知识文档所有者部门。
     */
    private String ownerDept;

    /**
     * 知识文档状态。
     */
    private String status;

    /**
     * 知识文档当前版本ID。
     */
    private Long currentVersionId;

    /**
     * 知识文档摘要。
     */
    private String summary;

    /**
     * 知识文档当前版本号。
     */
    private String currentVersionNo;

    /**
     * 知识文档解析状态。
     */
    private String parseStatus;

    /**
     * 向量状态：PENDING-待处理、PROCESSING-处理中、COMPLETED-已完成、FAILED-失败
     */
    private String vectorStatus;

    /**
     * 知识文档分片数量。
     */
    private Integer chunkCount;

    /**
     * 知识文档创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 知识文档更新时间。
     */
    private LocalDateTime updatedAt;
}