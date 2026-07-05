package org.example.ai.agent.modules.knowledgebase.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;

public interface KnowledgeChunkService extends IService<KnowledgeChunk> {

    /**
     * 按文档版本查询切片列表。
     *
     * 用于管理端查看某个版本实际参与 RAG 检索的文本片段。
     */
    Page<KnowledgeChunk> findChunksByDocumentVersionId(Page<KnowledgeChunk>page,String keyword);

    /**
     * 启用或禁用切片。
     *
     * enabled=false 后，该切片后续不应参与正式检索。
     * 注意：当前只改 MySQL 状态，下一步再让查询服务过滤 enabled chunk。
     */
    void updateEnabled(Long id, Integer enabled);
}
