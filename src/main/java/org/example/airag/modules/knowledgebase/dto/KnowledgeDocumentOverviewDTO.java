// 文件：src/main/java/org/example/airag/modules/knowledgebase/dto/KnowledgeDocumentOverviewDTO.java

package org.example.airag.modules.knowledgebase.dto;

import lombok.Data;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.airag.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocumentVersion;

import java.util.List;

/**
 * 企业知识文档详情聚合信息。
 *
 * 用于管理端一次性查看文档、版本、切片和最近向量化任务状态。
 */
@Data
public class KnowledgeDocumentOverviewDTO {

    /**
     * 文档主信息。
     */
    private KnowledgeDocument document;

    /**
     * 当前发布版本。
     */
    private KnowledgeDocumentVersion currentVersion;

    /**
     * 文档所有版本。
     */
    private List<KnowledgeDocumentVersion> versions;

    /**
     * 当前版本切片总数。
     */
    private Long totalChunkCount;

    /**
     * 当前版本启用切片数。
     */
    private Long enabledChunkCount;

    /**
     * 当前版本禁用切片数。
     */
    private Long disabledChunkCount;

    /**
     * 最近一次向量化任务。
     */
    private KnowledgeBaseVectorTask latestVectorTask;

    /**
     * 切片集合
     */
    private List<KnowledgeChunk> chunkList;
}