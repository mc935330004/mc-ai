package org.example.ai.agent.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeCategory;

@Mapper
public interface KnowledgeCategoryMapper extends BaseMapper<KnowledgeCategory> {

    /**
     * 获取知识分类列表。
     */
    Page<KnowledgeCategory> findKnowledgeCategoryPageList(Page<KnowledgeCategory>page,
                                                          @Param("keyword") String keyword);
}
