package org.example.airag.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseListItemDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseSerDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;


/**
 * 知识库文件 Mapper
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    /**
     * 获取知识库列表
     * @param dto
     * @return
     */
    Page<KnowledgeBaseListItemDTO> listKnowledgeBases(Page<KnowledgeBaseListItemDTO> page,
                                                      @Param("dto") KnowledgeBaseSerDTO dto);


}