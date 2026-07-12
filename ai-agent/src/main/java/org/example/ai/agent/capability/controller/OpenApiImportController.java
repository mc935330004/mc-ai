package org.example.ai.agent.capability.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.OpenApiImportRequest;
import org.example.ai.agent.capability.dto.OpenApiOperationDetailRequest;
import org.example.ai.agent.capability.dto.OpenApiPreviewRequest;
import org.example.ai.agent.capability.dto.OpenApiSyncRequest;
import org.example.ai.agent.capability.service.OpenApiImportService;
import org.example.ai.agent.capability.service.OpenApiPreviewService;
import org.example.ai.agent.capability.service.OpenApiSyncService;
import org.example.ai.agent.capability.vo.*;
import org.example.ai.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;

/**
 * OpenAPI 能力导入接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/capabilityOpenapi")
public class OpenApiImportController {

    private final OpenApiPreviewService openApiPreviewService;
    private final HttpServletRequest httpServletRequest;
    private final OpenApiImportService openApiImportService;
    private final OpenApiSyncService openApiSyncService;
    /**
     * 扫描 OpenAPI 文档并返回能力候选项。
     *
     * 当前接口只预览，不保存能力和字段字典。
     */
    @PostMapping("/preview")
    public Result<OpenApiPreviewVO> preview(@RequestBody OpenApiPreviewRequest request) {
        String authorization = httpServletRequest.getHeader("Authorization");
        return Result.success(openApiPreviewService.preview(request,authorization));
    }

    /**
     * 查询某一个 OpenAPI 接口的完整 Schema。
     *
     * 列表不返回完整结构，用户展开接口时再调用。
     */
    @PostMapping("/operationDetail")
    public Result<OpenApiOperationDetailVO> operationDetail( @RequestBody OpenApiOperationDetailRequest request) {
        String authorization = httpServletRequest.getHeader("Authorization" );
        return Result.success(openApiPreviewService.operationDetail(request,authorization) );
    }

    /**
     * 批量导入选中的 OpenAPI 接口。
     *
     * 导入后的能力统一为 DRAFT，不会立即被 Agent 调用。
     */
    @PostMapping("/import")
    public Result<OpenApiImportResultVO> importCapabilities(@RequestBody OpenApiImportRequest request) {
        String authorization = httpServletRequest.getHeader("Authorization");
        return Result.success(openApiImportService.importCapabilities(request,authorization));
    }

    /**
     * 预览OpenAPI与当前能力配置的差异。
     *
     * 不修改数据库。
     */
    @PostMapping("/sync-preview")
    public Result<OpenApiSyncPreviewVO> syncPreview( @RequestBody OpenApiSyncRequest request ) {
        String authorization = httpServletRequest.getHeader("Authorization");
        return Result.success(openApiSyncService.preview(request,authorization));
    }

    /**
     * 重新计算并应用OpenAPI变化。
     */
    @PostMapping("/sync")
    public Result<OpenApiSyncApplyResultVO> sync(@RequestBody OpenApiSyncRequest request) {
        String authorization =httpServletRequest.getHeader("Authorization");
        return Result.success(openApiSyncService.apply(request,authorization));
    }
}