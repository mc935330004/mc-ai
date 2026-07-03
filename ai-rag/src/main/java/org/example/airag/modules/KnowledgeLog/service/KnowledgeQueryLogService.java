package org.example.airag.modules.KnowledgeLog.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.apache.ibatis.annotations.Param;
import org.example.airag.modules.KnowledgeLog.dto.KnowledgeQueryStatsDTO;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryLog;

public interface KnowledgeQueryLogService extends IService<KnowledgeQueryLog> {

    /**
     * 查询知识查询日志列表
     */
    Page<KnowledgeQueryLog> findKnowledgeQueryLogList(Page<KnowledgeQueryLog> page, String status,String answer);

    /**
     * 知识查询日志详情
     */
    KnowledgeQueryLog getKnowledgeQueryLogDetail(Long id);

    /**
     * 企业问答的统计
     */
    KnowledgeQueryStatsDTO getEnterpriseQuestionStatistics();
}