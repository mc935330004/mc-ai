package org.example.ai.agent.modules.KnowledgeLog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.modules.KnowledgeLog.dto.KnowledgeQueryStatsDTO;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryLog;

@Mapper
public interface KnowledgeQueryLogMapper extends BaseMapper<KnowledgeQueryLog> {

    /**
     * 查询知识查询日志列表
     */
    Page<KnowledgeQueryLog> findKnowledgeQueryLogList(Page<KnowledgeQueryLog> page,
                                                      @Param("status") String status,
                                                      @Param("answer") String answer);

    /**
     * 企业问答的统计
     */
    KnowledgeQueryStatsDTO getEnterpriseQuestionStatistics();
}