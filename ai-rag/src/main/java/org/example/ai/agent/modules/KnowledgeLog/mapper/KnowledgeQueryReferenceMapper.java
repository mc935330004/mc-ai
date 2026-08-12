package org.example.ai.agent.modules.KnowledgeLog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryReference;

@Mapper
public interface KnowledgeQueryReferenceMapper extends BaseMapper<KnowledgeQueryReference> {

    /**
     * 查询当前租户指定问答日志的引用来源。
     */
    Page<KnowledgeQueryReference> getReferences(
            Page<KnowledgeQueryReference> page,
            @Param("logId") Long logId,
            @Param("tenantId") Long tenantId
    );
}