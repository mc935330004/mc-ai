package org.example.ai.agent.capability.service;

import org.example.ai.agent.capability.dto.OpenApiOperationDetailRequest;
import org.example.ai.agent.capability.dto.OpenApiPreviewRequest;
import org.example.ai.agent.capability.vo.OpenApiOperationDetailVO;
import org.example.ai.agent.capability.vo.OpenApiPreviewVO;

/**
 * OpenAPI 扫描预览服务。
 */
public interface OpenApiPreviewService {

    /**
     * 扫描业务系统 OpenAPI 文档。
     *
     * 只返回候选能力，不写入数据库。
     */
    OpenApiPreviewVO preview(OpenApiPreviewRequest request, String authorization );

    /**
     * 查询某一个 OpenAPI 接口的完整 Schema。
     *
     * 列表不返回完整结构，用户展开接口时再调用。
     */
    OpenApiOperationDetailVO operationDetail(OpenApiOperationDetailRequest request,String authorization);
}