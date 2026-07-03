package org.example.airag.modules.KnowledgeLog.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.modules.KnowledgeLog.dto.KnowledgeQueryStatsDTO;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryLog;
import org.example.airag.modules.KnowledgeLog.mapper.KnowledgeQueryLogMapper;
import org.example.airag.modules.KnowledgeLog.service.KnowledgeQueryLogService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeQueryLogServiceImpl extends ServiceImpl<KnowledgeQueryLogMapper, KnowledgeQueryLog>
        implements KnowledgeQueryLogService {


    @Override
    public Page<KnowledgeQueryLog> findKnowledgeQueryLogList(Page<KnowledgeQueryLog> page, String status, String answer) {
        return baseMapper.findKnowledgeQueryLogList(page, status, answer);
    }

    @Override
    public KnowledgeQueryLog getKnowledgeQueryLogDetail(Long id) {
        return this.getById(id);
    }

    @Override
    public KnowledgeQueryStatsDTO getEnterpriseQuestionStatistics() {
        return  baseMapper.getEnterpriseQuestionStatistics();
    }
}