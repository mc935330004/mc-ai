package org.example.airag.modules.knowledgebase.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseListItemDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseSerDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseStatsDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;

import java.util.List;

/**
 * 知识库文件 Service
 */
public interface KnowledgeBaseService extends IService<KnowledgeBase> {

    /**
     * 获取知识库列表，支持向量状态过滤和简单排序。
     * @ param keyword 关键字，支持模糊匹配
     */
    Page<KnowledgeBaseListItemDTO>listKnowledgeBases(Page<KnowledgeBaseListItemDTO> page,KnowledgeBaseSerDTO dto);

    /**
     * 获取知识库详情
     */
    KnowledgeBaseListItemDTO getKnowledgeBase(Long id);

    /**
     * 获取所有知识库分类。
     *
     * <p>当前不单独建 category 表，直接从 knowledge_base.category 字段去重得到。</p>
     */
     List<String> getAllCategories();

    /**
     * 修改知识库数据信息
     */
    void updateKnowledgeBase(KnowledgeBase base);

    /**
     * 获取知识库统计信息。
     */
     KnowledgeBaseStatsDTO getStatistics();


}