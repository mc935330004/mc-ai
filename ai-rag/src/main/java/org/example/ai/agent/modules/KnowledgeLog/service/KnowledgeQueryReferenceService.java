package org.example.ai.agent.modules.KnowledgeLog.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryReference;

public interface KnowledgeQueryReferenceService extends IService<KnowledgeQueryReference> {

    /**
     * 根据日志ID获取引用来源列表。
     */
    Page<KnowledgeQueryReference> getReferences(Page<KnowledgeQueryReference>page,Long logId);
}