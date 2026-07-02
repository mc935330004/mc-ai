package org.example.airag.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.airag.modules.knowledgebase.vo.KnowledgeDocumentListItemVO;

@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {

    /**
     * 查询知识文档列表
     */
    Page<KnowledgeDocumentListItemVO> findPageList(Page<KnowledgeDocumentListItemVO> page,
                                                   @Param("query") KnowledgeDocumentDTO query);


}
