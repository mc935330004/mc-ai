package org.example.ai.agent.capability.service;

import org.example.ai.agent.capability.dto.OpenApiSyncRequest;
import org.example.ai.agent.capability.vo.OpenApiSyncApplyResultVO;
import org.example.ai.agent.capability.vo.OpenApiSyncPreviewVO;

/**
 * OpenAPI增量同步服务。
 */
public interface OpenApiSyncService {

    /**
     * 只预览差异，不修改数据库。
     */
    OpenApiSyncPreviewVO preview(OpenApiSyncRequest request,String authorization);

    /**
     * 重新计算差异并应用。
     */
    OpenApiSyncApplyResultVO apply( OpenApiSyncRequest request,String authorization);
}