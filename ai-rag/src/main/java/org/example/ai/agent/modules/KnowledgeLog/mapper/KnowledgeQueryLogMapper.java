package org.example.ai.agent.modules.KnowledgeLog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.modules.KnowledgeLog.dto.KnowledgeQueryStatsDTO;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryLog;

/**
 * 知识问答日志数据访问。
 */
@Mapper
public interface KnowledgeQueryLogMapper
        extends BaseMapper<KnowledgeQueryLog> {

    /**
     * 查询当前租户的知识问答日志。
     */
    Page<KnowledgeQueryLog> findKnowledgeQueryLogList(
            Page<KnowledgeQueryLog> page,
            @Param("tenantId") Long tenantId,
            @Param("status") String status,
            @Param("answer") String answer
    );

    /**
     * 统计当前租户的知识问答情况。
     */
    KnowledgeQueryStatsDTO getEnterpriseQuestionStatistics(
            @Param("tenantId") Long tenantId
    );
}