package org.example.ai.agent.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;

@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 按文档版本查询切片列表。
     *
     * 用于管理端查看某个版本实际参与 RAG 检索的文本片段。
     */
    Page<KnowledgeChunk> findChunksByDocumentVersionId(Page<KnowledgeChunk>page,
                                                       @Param("keyword") String keyword);
}
