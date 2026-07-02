// 文件：src/main/java/org/example/airag/modules/knowledgebase/dto/KnowledgeDocumentPageQuery.java

package org.example.airag.modules.knowledgebase.dto;

import lombok.Data;

/**
 * 企业知识文档分页查询条件。
 */
@Data
public class KnowledgeDocumentDTO {

    /**
     * 关键词：匹配标题、文档编号、摘要。
     */
    private String keyword;

    /**
     * 分类ID。
     */
    private Long categoryId;

    /**
     * 文档状态：DRAFT-草稿，PUBLISHED-已发布，DEPRECATED-已废止，ARCHIVED-已归档
     */
    private String status;

    /**
     * 归属部门。
     */
    private String ownerDept;


    /**
     * 向量状态：PENDING-待处理、PROCESSING-处理中、COMPLETED-已完成、FAILED-失败
     */
    private String vectorStatus;
}