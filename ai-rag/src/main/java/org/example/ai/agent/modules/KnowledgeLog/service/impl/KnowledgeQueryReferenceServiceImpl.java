package org.example.ai.agent.modules.KnowledgeLog.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryReference;
import org.example.ai.agent.modules.KnowledgeLog.mapper.KnowledgeQueryReferenceMapper;
import org.example.ai.agent.modules.KnowledgeLog.service.KnowledgeQueryReferenceService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeQueryReferenceServiceImpl extends ServiceImpl<KnowledgeQueryReferenceMapper, KnowledgeQueryReference>
        implements KnowledgeQueryReferenceService {
    @Override
    public Page<KnowledgeQueryReference> getReferences(Page<KnowledgeQueryReference> page, Long logId) {
        return baseMapper.getReferences(page, logId);
    }
}