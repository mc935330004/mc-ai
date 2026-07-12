package org.example.ai.agent.capability.service;

import org.example.ai.agent.capability.dto.OpenApiImportRequest;
import org.example.ai.agent.capability.vo.OpenApiImportResultVO;

/**
 * OpenAPI 能力导入服务。
 */
public interface OpenApiImportService {

    /**
     * 批量导入能力草稿。
     */
    OpenApiImportResultVO importCapabilities(OpenApiImportRequest request,
            String authorization);
}