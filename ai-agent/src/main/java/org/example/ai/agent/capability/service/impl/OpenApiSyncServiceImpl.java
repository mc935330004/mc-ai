package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.*;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.service.OpenApiImportService;
import org.example.ai.agent.capability.service.OpenApiPreviewService;
import org.example.ai.agent.capability.service.OpenApiSyncService;
import org.example.ai.agent.capability.vo.*;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * OpenAPI增量同步实现。
 */
@Service
@RequiredArgsConstructor
public class OpenApiSyncServiceImpl implements OpenApiSyncService {

    private final OpenApiPreviewService openApiPreviewService;
    private final OpenApiImportService openApiImportService;
    private final CapabilityDefinitionMapper capabilityMapper;
    private final ObjectMapper objectMapper;

    @Override
    public OpenApiSyncPreviewVO preview(OpenApiSyncRequest request, String authorization) {
        return calculateDiff(request, authorization);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OpenApiSyncApplyResultVO apply(OpenApiSyncRequest request,String authorization) {
        // 应用前重新读取OpenAPI，不能相信之前返回给前端的差异。
        OpenApiSyncPreviewVO preview =calculateDiff(request, authorization);

        int added = 0;
        int updated = 0;
        int disabled = 0;

        for (OpenApiSyncItemVO change : preview.getChanges()) {
            switch (change.getChangeType()) {
                case "ADDED" -> {
                    importAdded( request.getSystemCode(), change,authorization);
                    added++;
                }
                case "CHANGED" -> {
                    updateChanged(request.getSystemCode(),change,authorization);
                    updated++;
                }
                case "REMOVED" -> {
                    disableRemoved(change);
                    disabled++;
                }
                default -> {
                    // UNCHANGED不需要处理。
                }
            }
        }

        return OpenApiSyncApplyResultVO.builder()
                .systemCode(request.getSystemCode())
                .added(added)
                .updated(updated)
                .disabled(disabled)
                .build();
    }

    /**
     * 计算数据库能力与最新OpenAPI之间的差异。
     */
    private OpenApiSyncPreviewVO calculateDiff( OpenApiSyncRequest request,
            String authorization ) {
        List<OpenApiOperationPreviewVO> remoteOperations =
                loadAllOperations( request.getSystemCode(),authorization);

        List<CapabilityDefinition> localCapabilities =
                capabilityMapper.selectList(new LambdaQueryWrapper<CapabilityDefinition>()
                                .eq(CapabilityDefinition::getSystemCode,request.getSystemCode())
                                .eq(CapabilityDefinition::getSourceType,"OPENAPI") );

        Map<String, CapabilityDefinition> localMap =
                localCapabilities.stream() .filter(item -> StringUtils.hasText(
                                item.getSourceOperationId()))
                        .collect(Collectors.toMap(CapabilityDefinition::getSourceOperationId,
                                Function.identity(),(first, duplicate) -> first));

        Map<String, OpenApiOperationPreviewVO> remoteMap =
                remoteOperations.stream().filter(item -> StringUtils.hasText(
                                item.getOperationId()))
                        .collect(Collectors.toMap( OpenApiOperationPreviewVO::getOperationId,
                                Function.identity(),
                                (first, duplicate) -> first));

        List<OpenApiSyncItemVO> changes =new ArrayList<>();

        // 检查新增和变更。
        for (OpenApiOperationPreviewVO remote : remoteOperations) {
            CapabilityDefinition local = localMap.get(remote.getOperationId());
            if (local == null) {
                changes.add(buildAdded(remote));
                continue;
            }
            OpenApiOperationDetailVO detail =loadDetail( request.getSystemCode(), remote,authorization);

            boolean methodChanged = !equalsIgnoreCase( local.getMethod(), detail.getMethod() );

            boolean urlChanged = !Objects.equals( local.getUrl(), detail.getUrl());

            boolean inputChanged =!jsonEquals( local.getInputSchemaJson(),detail.getInputSchemaJson() );

            boolean outputChanged = !jsonEquals( local.getOutputSchemaJson(), detail.getOutputSchemaJson() );

            boolean changed = methodChanged  || urlChanged || inputChanged || outputChanged;

            changes.add( OpenApiSyncItemVO.builder() .changeType( changed ? "CHANGED" : "UNCHANGED" )
                            .operationId(remote.getOperationId() )
                            .capabilityCode(local.getCapabilityCode())
                            .capabilityName(local.getCapabilityName())
                            .oldMethod(local.getMethod())
                            .newMethod(detail.getMethod())
                            .oldUrl(local.getUrl())
                            .newUrl(detail.getUrl())
                            .inputSchemaChanged(inputChanged)
                            .outputSchemaChanged(outputChanged)
                            .build()
            );
        }

        // 检查OpenAPI中已经消失的接口。
        for (CapabilityDefinition local : localCapabilities) {
            if (!StringUtils.hasText( local.getSourceOperationId())) {
                continue;
            }

            if (!remoteMap.containsKey(local.getSourceOperationId())) {
                changes.add( OpenApiSyncItemVO.builder() .changeType("REMOVED")
                                .operationId( local.getSourceOperationId() )
                                .capabilityCode( local.getCapabilityCode())
                                .capabilityName(local.getCapabilityName() )
                                .oldMethod(local.getMethod())
                                .oldUrl(local.getUrl())
                                .inputSchemaChanged(false)
                                .outputSchemaChanged(false)
                                .build()
                );
            }
        }
        return buildPreview(
                request.getSystemCode(),
                changes
        );
    }

    /**
     * 分页读取全部OpenAPI接口摘要。
     */
    private List<OpenApiOperationPreviewVO>
    loadAllOperations(String systemCode, String authorization) {
        List<OpenApiOperationPreviewVO> result = new ArrayList<>();
        int current = 1;
        int size = 100;
        while (true) {
            OpenApiPreviewRequest request = new OpenApiPreviewRequest();
            request.setSystemCode(systemCode);
            request.setCurrent(current);
            request.setSize(size);
            OpenApiPreviewVO page = openApiPreviewService.preview(request, authorization);

            if (page.getInterfaces() != null) {
                result.addAll(page.getInterfaces());
            }
            if (result.size() >= page.getTotal() || page.getInterfaces() == null || page.getInterfaces().isEmpty()) {
                break;
            }
            current++;
        }
        return result;
    }

    private OpenApiOperationDetailVO loadDetail( String systemCode, OpenApiOperationPreviewVO operation,
            String authorization ) {
        OpenApiOperationDetailRequest request =new OpenApiOperationDetailRequest();

        request.setSystemCode(systemCode);
        request.setUrl(operation.getUrl());
        request.setMethod(operation.getMethod());
        return openApiPreviewService.operationDetail(request,authorization);
    }

    private OpenApiSyncItemVO buildAdded( OpenApiOperationPreviewVO remote) {
        return OpenApiSyncItemVO.builder()
                .changeType("ADDED")
                .operationId(remote.getOperationId())
                .capabilityCode(remote.getCapabilityCode())
                .capabilityName(remote.getCapabilityName())
                .newMethod(remote.getMethod())
                .newUrl(remote.getUrl())
                .inputSchemaChanged(true)
                .outputSchemaChanged(true)
                .build();
    }

    /**
     * 新接口复用现有批量导入逻辑，统一进入DRAFT。
     */
    private void importAdded(String systemCode,OpenApiSyncItemVO change,String authorization ) {
        OpenApiImportItemDTO item =new OpenApiImportItemDTO();

        item.setUrl(change.getNewUrl());
        item.setMethod(change.getNewMethod());
        item.setCapabilityCode(change.getCapabilityCode());
        item.setCapabilityName(change.getCapabilityName());

        OpenApiImportRequest request = new OpenApiImportRequest();

        request.setSystemCode(systemCode);
        request.setInterfaces(List.of(item));

        OpenApiImportResultVO result =openApiImportService.importCapabilities(request,authorization);
        if (result.getFailed() > 0) {
            throw new BusinessException( 500,"新增接口同步失败："+ change.getOperationId());
        }
    }

    /**
     * 更新变化的技术字段，保留人工维护的业务语义。
     */
    private void updateChanged(String systemCode,OpenApiSyncItemVO change, String authorization) {
        OpenApiOperationPreviewVO operation =
                OpenApiOperationPreviewVO.builder()
                        .operationId(change.getOperationId())
                        .url(change.getNewUrl())
                        .method(change.getNewMethod())
                        .build();

        OpenApiOperationDetailVO detail =loadDetail( systemCode, operation, authorization );

        CapabilityDefinition capability = capabilityMapper.selectOne(new LambdaQueryWrapper<CapabilityDefinition>()
                                .eq(CapabilityDefinition ::getSystemCode,systemCode)
                                .eq(CapabilityDefinition ::getSourceOperationId,change.getOperationId()));

        if (capability == null) {
            throw new BusinessException(404,"待同步能力不存在：" + change.getOperationId());
        }

        // 只更新技术字段，不覆盖名称、说明和副作用等级。
        capability.setMethod(detail.getMethod());
        capability.setUrl(detail.getUrl());
        capability.setInputSchemaJson(detail.getInputSchemaJson());
        capability.setOutputSchemaJson(detail.getOutputSchemaJson());
        // 接口结构变化后必须重新审核发布。
        capability.setPublishStatus("DRAFT");
        capability.setEnabled(0);
        capability.setUpdatedAt(LocalDateTime.now());

        capabilityMapper.updateById(capability);
    }

    /**
     * OpenAPI中消失的接口不删除，只停用。
     */
    private void disableRemoved(OpenApiSyncItemVO change) {
        CapabilityDefinition capability =capabilityMapper.selectOne( new LambdaQueryWrapper<CapabilityDefinition>()
                                .eq(CapabilityDefinition ::getCapabilityCode,change.getCapabilityCode()));
        if (capability == null) {
            return;
        }
        capability.setEnabled(0);
        capability.setPublishStatus("DISABLED");
        capability.setUpdatedAt(LocalDateTime.now());
        capabilityMapper.updateById(capability);
    }

    private boolean jsonEquals( String left,String right) {
        if (!StringUtils.hasText(left) && !StringUtils.hasText(right)) {
            return true;
        }
        try {
            JsonNode leftNode = objectMapper.readTree(StringUtils.hasText(left)? left : "{}");
            JsonNode rightNode =objectMapper.readTree( StringUtils.hasText(right) ? right : "{}");
            return leftNode.equals(rightNode);
        } catch (Exception e) {
            return Objects.equals(left, right);
        }
    }

    private boolean equalsIgnoreCase(String left,String right) {
        return left == null ? right == null : left.equalsIgnoreCase(right);
    }

    private OpenApiSyncPreviewVO buildPreview(String systemCode, List<OpenApiSyncItemVO> changes) {
        return OpenApiSyncPreviewVO.builder()
                .systemCode(systemCode)
                .added(count(changes, "ADDED"))
                .removed(count(changes, "REMOVED"))
                .changed(count(changes, "CHANGED"))
                .unchanged(count(changes, "UNCHANGED"))
                .changes(changes)
                .build();
    }

    private int count(List<OpenApiSyncItemVO> changes,String type) {
        return (int) changes.stream().filter(item -> type.equals(item.getChangeType()))
                .count();
    }
}