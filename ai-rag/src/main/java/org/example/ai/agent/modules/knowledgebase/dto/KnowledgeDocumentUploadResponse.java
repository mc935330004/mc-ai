package org.example.ai.agent.modules.knowledgebase.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 企业知识文档上传响应。
 */
@Data
@AllArgsConstructor
public class KnowledgeDocumentUploadResponse {

    /**
     * 文档主表 ID。
     */
    private Long documentId;

    /**
     * 文档版本 ID。
     */
    private Long versionId;

    /**
     * 文档标题。
     */
    private String title;

    /**
     * 当前版本号。
     */
    private String versionNo;

    /**
     * 向量化任务 ID。
     */
    private Long vectorTaskId;

    /**
     * 解析状态：PENDING待处理，PROCESSING处理中，COMPLETED成功，FAILED失败。
     */
    private String parseStatus;

    /**
     * 向量化状态：PENDING待处理，PROCESSING处理中，COMPLETED成功，FAILED失败。
     */
    private String vectorStatus;
}