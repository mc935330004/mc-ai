package org.example.airag.modules.KnowledgeLog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryReference;

@Mapper
public interface KnowledgeQueryReferenceMapper extends BaseMapper<KnowledgeQueryReference> {

    /**
     * 根据日志ID获取引用来源列表。
     */
    Page<KnowledgeQueryReference> getReferences(Page<KnowledgeQueryReference>page,
                                                @Param("logId") Long logId);
}