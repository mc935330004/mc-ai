package org.example.ai.agent.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;

@Mapper
public interface KnowledgeDocumentVersionMapper extends BaseMapper<KnowledgeDocumentVersion> {
}
