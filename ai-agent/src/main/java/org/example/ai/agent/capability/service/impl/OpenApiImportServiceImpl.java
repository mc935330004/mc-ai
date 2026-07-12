package org.example.ai.agent.capability.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.OpenApiImportItemDTO;
import org.example.ai.agent.capability.dto.OpenApiImportRequest;
import org.example.ai.agent.capability.dto.OpenApiOperationDetailRequest;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.BusinessSystemService;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.OpenApiImportService;
import org.example.ai.agent.capability.service.OpenApiPreviewService;
import org.example.ai.agent.capability.vo.OpenApiImportItemResultVO;
import org.example.ai.agent.capability.vo.OpenApiImportResultVO;
import org.example.ai.agent.capability.vo.OpenApiOperationDetailVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OpenAPI 能力批量导入实现。
 */
@Service
@RequiredArgsConstructor
public class OpenApiImportServiceImpl
        implements OpenApiImportService {

    private final BusinessSystemService businessSystemService;
    private final OpenApiPreviewService openApiPreviewService;
    private final CapabilityDefinitionService capabilityDefinitionService;

    @Override
    public OpenApiImportResultVO importCapabilities(OpenApiImportRequest request, String authorization) {
        BusinessSystem system = businessSystemService.getEnabledByCode( request.getSystemCode() );
        if (system == null) {
            throw new BusinessException(404,"业务系统不存在或未启用：" + request.getSystemCode());
        }
        List<OpenApiImportItemResultVO> results = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (OpenApiImportItemDTO item : request.getInterfaces()) {
            try {
                String capabilityCode = importOne(system,item,authorization);
                if (capabilityCode == null) {
                    skipped++;
                    results.add(buildResult( item,null,"SKIPPED",
                            "能力已存在，未重复导入"));
                } else {
                    imported++;
                    results.add(buildResult(item,capabilityCode,
                            "IMPORTED",
                            "已导入为草稿"));
                }
            } catch (Exception e) {
                failed++;
                results.add(buildResult(item,item.getCapabilityCode(),
                        "FAILED",
                        e.getMessage()));
            }
        }
        return OpenApiImportResultVO.builder()
                .systemCode(system.getSystemCode())
                .total(request.getInterfaces().size())
                .imported(imported)
                .skipped(skipped)
                .failed(failed)
                .results(results)
                .build();
    }

    /**
     * 导入单个接口。
     *
     * 返回 null 表示能力已经存在。
     */
    private String importOne(BusinessSystem system,OpenApiImportItemDTO item,
            String authorization ) {
        OpenApiOperationDetailRequest detailRequest =new OpenApiOperationDetailRequest();

        detailRequest.setSystemCode(system.getSystemCode());
        detailRequest.setUrl(item.getUrl());
        detailRequest.setMethod(item.getMethod());

        // Schema 必须由后端重新读取，不能直接使用前端提交的数据。
        OpenApiOperationDetailVO detail =openApiPreviewService.operationDetail(detailRequest,authorization);

        String capabilityCode = StringUtils.hasText( item.getCapabilityCode()) ? item.getCapabilityCode().trim()
                : detail.getCapabilityCode();
        if (!StringUtils.hasText(capabilityCode)) {
            throw new BusinessException(400, "能力编码不能为空");
        }
        boolean exists = capabilityDefinitionService.lambdaQuery()
                .eq(CapabilityDefinition::getCapabilityCode,capabilityCode)
                .exists();
        if (exists) {
            return null;
        }
        String sideEffect = StringUtils.hasText(item.getSideEffect())
                ? item.getSideEffect().trim().toUpperCase(Locale.ROOT): detail.getSideEffect();

        if (!List.of("READ","WRITE","DANGEROUS").contains(sideEffect)) {
            throw new BusinessException(400,"不支持的副作用类型：" + sideEffect);
        }
        CapabilityDefinition entity = new CapabilityDefinition();
        entity.setCapabilityCode(capabilityCode);
        entity.setCapabilityName(StringUtils.hasText(item.getCapabilityName())
                        ? item.getCapabilityName().trim() : detail.getCapabilityName());
        entity.setSystemCode(system.getSystemCode());
        entity.setDomain(system.getSystemCode().toLowerCase(Locale.ROOT));
        entity.setModuleName(detail.getTag());
        entity.setDescription(StringUtils.hasText(detail.getDescription())
                        ? detail.getDescription()
                        : detail.getCapabilityName());
        entity.setMethod(detail.getMethod());
        entity.setUrl(detail.getUrl());
        entity.setSideEffect(sideEffect);
        entity.setRequireConfirm(!"READ".equals(sideEffect));
        entity.setInputSchemaJson( detail.getInputSchemaJson());
        entity.setOutputSchemaJson(detail.getOutputSchemaJson());
        entity.setRequestContentType("application/json");

        // OpenAPI 导入的能力必须先进入草稿状态。
        entity.setSourceType("OPENAPI");
        entity.setSourceOperationId(detail.getOperationId());
        entity.setPublishStatus("DRAFT");

        // 草稿能力双重禁用，审核发布后再启用。
        entity.setEnabled(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        capabilityDefinitionService.save(entity);
        return capabilityCode;
    }

    private OpenApiImportItemResultVO buildResult(OpenApiImportItemDTO item,String capabilityCode,
            String status,String message) {
        return OpenApiImportItemResultVO.builder()
                .url(item.getUrl())
                .method(item.getMethod())
                .capabilityCode(capabilityCode)
                .status(status)
                .message(message)
                .build();
    }
}