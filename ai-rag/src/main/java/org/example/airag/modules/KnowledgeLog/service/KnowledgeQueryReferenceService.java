package org.example.airag.modules.KnowledgeLog.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryReference;

import java.util.List;

public interface KnowledgeQueryReferenceService extends IService<KnowledgeQueryReference> {

    /**
     * 根据日志ID获取引用来源列表。
     */
    Page<KnowledgeQueryReference> getReferences(Page<KnowledgeQueryReference>page,Long logId);
}