package org.example.ai.agent.modules.knowledgebase.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentOverviewDTO;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.vo.KnowledgeDocumentListItemVO;

public interface KnowledgeDocumentService extends IService<KnowledgeDocument> {

    /**
     * 获取知识文档总概览
     */
    KnowledgeDocumentOverviewDTO overview(Long id);

    /**
     * 查询知识文档列表
     */
    Page<KnowledgeDocumentListItemVO> findPageList(Page<KnowledgeDocumentListItemVO> page, KnowledgeDocumentDTO query);

    /**
     * 废止文档。
     *
     * 废止后文档不再参与正式问答，但保留历史版本和切片。
     */
    void deprecatedDocument(Long documentId);

    /**
     * 归档文档。
     *
     * 归档后文档不再参与正式问答，适用于历史资料保留。
     */
    void archiveDocument(Long documentId);

    /**
     * 恢复文档为已发布状态。
     *
     * 只有当前版本解析和向量化完成后，才允许恢复。
     */
    void restorePublished(Long documentId);

    /**
     * 向量任务监控列表（分页）
     */
    Page<KnowledgeBaseVectorTask> findVectorTaskList(Page<KnowledgeBaseVectorTask> page, KnowledgeDocumentDTO query);
}
