package org.example.ai.agent.access.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.access.dto.ResourceAccessSaveDTO;
import org.example.ai.agent.access.entity.ResourceUserGrant;
import org.example.ai.agent.access.mapper.ResourceUserGrantMapper;
import org.example.ai.agent.access.model.ExecutableResourceType;
import org.example.ai.agent.access.model.ResourceAccessScope;
import org.example.ai.agent.access.service.ResourceAccessManagementService;
import org.example.ai.agent.access.vo.ResourceAccessVO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 可执行资源人员访问配置服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceAccessManagementServiceImpl
        implements ResourceAccessManagementService {

    private static final int MAX_USER_COUNT = 200;

    private final ResourceUserGrantMapper grantMapper;
    private final CapabilityDefinitionMapper capabilityMapper;
    private final WorkflowDefinitionMapper workflowMapper;

    /**
     * 查询当前访问范围和指定人员名单。
     */
    @Override
    public ResourceAccessVO getAccess(
            String resourceType,
            Long resourceId) {

        ExecutableResourceType type =
                ExecutableResourceType.from(resourceType);

        ResourceSummary resource =
                loadResource(type, resourceId, false);

        List<String> userIds =
                ResourceAccessScope.RESTRICTED.name()
                        .equals(resource.accessScope())
                        ? listUserIds(type, resourceId)
                        : List.of();

        return buildVO(type, resource, userIds);
    }

    /**
     * 在同一事务内更新访问范围并完整替换人员名单。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResourceAccessVO saveAccess(
            String resourceType,
            Long resourceId,
            ResourceAccessSaveDTO dto,
            String operatorId) {

        ExecutableResourceType type =
                ExecutableResourceType.from(resourceType);
        ResourceAccessScope scope =
                parseScope(dto);
        String normalizedOperator =
                normalizeOperator(operatorId);
        List<String> userIds =
                normalizeUserIds(dto, scope);

        ResourceSummary resource =
                loadResource(type, resourceId, true);

        updateAccessScope(type, resourceId, scope);
        replaceGrants(
                type,
                resourceId,
                userIds,
                normalizedOperator
        );

        log.info(
                "资源运行权限已更新，operatorId={}, resourceType={}, resourceId={}, accessScope={}, grantCount={}",
                normalizedOperator,
                type.name(),
                resourceId,
                scope.name(),
                userIds.size()
        );

        ResourceSummary updated =
                new ResourceSummary(
                        resource.id(),
                        resource.code(),
                        resource.name(),
                        scope.name()
                );

        return buildVO(type, updated, userIds);
    }

    /**
     * 解析并校验访问范围。
     */
    private ResourceAccessScope parseScope(
            ResourceAccessSaveDTO dto) {

        if (dto == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "访问配置不能为空"
            );
        }

        return ResourceAccessScope.from(
                dto.getAccessScope()
        );
    }

    /**
     * 清理人员编码并保持配置顺序。
     */
    private List<String> normalizeUserIds(
            ResourceAccessSaveDTO dto,
            ResourceAccessScope scope) {

        if (scope == ResourceAccessScope.PUBLIC) {
            return List.of();
        }

        List<String> source = dto.getUserIds() == null
                ? List.of()
                : dto.getUserIds();
        LinkedHashSet<String> normalized =
                new LinkedHashSet<>();

        for (String userId : source) {
            if (!StringUtils.hasText(userId)) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "人员编码不能为空"
                );
            }

            String value = userId.trim();

            if (value.length() > 64) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "人员编码不能超过64个字符"
                );
            }

            normalized.add(value);
        }

        if (normalized.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "指定人员访问至少需要配置一名人员"
            );
        }

        if (normalized.size() > MAX_USER_COUNT) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "单个资源最多配置200名人员"
            );
        }

        return new ArrayList<>(normalized);
    }

    /**
     * 校验配置操作人。
     */
    private String normalizeOperator(String operatorId) {
        if (!StringUtils.hasText(operatorId)) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED,
                    "当前登录用户不能为空"
            );
        }

        String value = operatorId.trim();

        if (value.length() > 64) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "当前登录用户编码不能超过64个字符"
            );
        }

        return value;
    }

    /**
     * 根据资源类型读取定义；保存时锁定定义行。
     */
    private ResourceSummary loadResource(
            ExecutableResourceType type,
            Long resourceId,
            boolean forUpdate) {

        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "资源ID必须大于0"
            );
        }

        return type == ExecutableResourceType.CAPABILITY
                ? loadCapability(resourceId, forUpdate)
                : loadWorkflow(resourceId, forUpdate);
    }

    /**
     * 读取能力定义摘要。
     */
    private ResourceSummary loadCapability(
            Long resourceId,
            boolean forUpdate) {

        CapabilityDefinition definition = forUpdate
                ? capabilityMapper.selectByIdForUpdate(resourceId)
                : capabilityMapper.selectById(resourceId);

        if (definition == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "能力不存在：" + resourceId
            );
        }

        return new ResourceSummary(
                definition.getId(),
                definition.getCapabilityCode(),
                definition.getCapabilityName(),
                normalizeStoredScope(definition.getAccessScope())
        );
    }

    /**
     * 读取工作流定义摘要。
     */
    private ResourceSummary loadWorkflow(
            Long resourceId,
            boolean forUpdate) {

        WorkflowDefinition definition = forUpdate
                ? workflowMapper.selectByIdForUpdate(resourceId)
                : workflowMapper.selectById(resourceId);

        if (definition == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "工作流不存在：" + resourceId
            );
        }

        return new ResourceSummary(
                definition.getId(),
                definition.getWorkflowCode(),
                definition.getWorkflowName(),
                normalizeStoredScope(definition.getAccessScope())
        );
    }

    /**
     * 兼容数据库升级前尚未补齐的空访问范围。
     */
    private String normalizeStoredScope(String accessScope) {
        if (!StringUtils.hasText(accessScope)) {
            return ResourceAccessScope.PUBLIC.name();
        }

        try {
            return ResourceAccessScope.valueOf(
                    accessScope.trim().toUpperCase(Locale.ROOT)
            ).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "数据库中的资源访问范围无效：" + accessScope,
                    exception
            );
        }
    }

    /**
     * 只更新定义主表的当前访问策略。
     */
    private void updateAccessScope(
            ExecutableResourceType type,
            Long resourceId,
            ResourceAccessScope scope) {

        if (type == ExecutableResourceType.CAPABILITY) {
            capabilityMapper.update(
                    null,
                    Wrappers.<CapabilityDefinition>lambdaUpdate()
                            .eq(CapabilityDefinition::getId, resourceId)
                            .set(
                                    CapabilityDefinition::getAccessScope,
                                    scope.name()
                            )
            );
        } else {
            workflowMapper.update(
                    null,
                    Wrappers.<WorkflowDefinition>lambdaUpdate()
                            .eq(WorkflowDefinition::getId, resourceId)
                            .set(
                                    WorkflowDefinition::getAccessScope,
                                    scope.name()
                    )
            );
        }
    }

    /**
     * 完整替换指定资源的人员授权。
     */
    private void replaceGrants(
            ExecutableResourceType type,
            Long resourceId,
            List<String> userIds,
            String operatorId) {

        grantMapper.delete(
                Wrappers.<ResourceUserGrant>lambdaQuery()
                        .eq(
                                ResourceUserGrant::getResourceType,
                                type.name()
                        )
                        .eq(
                                ResourceUserGrant::getResourceId,
                                resourceId
                        )
        );

        LocalDateTime now = LocalDateTime.now();

        for (String userId : userIds) {
            ResourceUserGrant grant =
                    new ResourceUserGrant();
            grant.setResourceType(type.name());
            grant.setResourceId(resourceId);
            grant.setUserId(userId);
            grant.setCreatedBy(operatorId);
            grant.setCreatedAt(now);
            grantMapper.insert(grant);
        }
    }

    /**
     * 按配置顺序查询人员编码。
     */
    private List<String> listUserIds(
            ExecutableResourceType type,
            Long resourceId) {

        return grantMapper.selectList(
                        Wrappers.<ResourceUserGrant>lambdaQuery()
                                .eq(
                                        ResourceUserGrant::getResourceType,
                                        type.name()
                                )
                                .eq(
                                        ResourceUserGrant::getResourceId,
                                        resourceId
                                )
                                .orderByAsc(
                                        ResourceUserGrant::getId
                                )
                )
                .stream()
                .map(ResourceUserGrant::getUserId)
                .toList();
    }

    /**
     * 构建不暴露持久化实体的接口返回对象。
     */
    private ResourceAccessVO buildVO(
            ExecutableResourceType type,
            ResourceSummary resource,
            List<String> userIds) {

        List<String> safeUserIds = userIds == null
                ? List.of()
                : List.copyOf(userIds);

        return ResourceAccessVO.builder()
                .resourceType(type.name())
                .resourceId(resource.id())
                .resourceCode(resource.code())
                .resourceName(resource.name())
                .accessScope(resource.accessScope())
                .userIds(safeUserIds)
                .userCount(safeUserIds.size())
                .build();
    }

    /**
     * 统一承载能力和工作流的管理端展示字段。
     */
    private record ResourceSummary(
            Long id,
            String code,
            String name,
            String accessScope) {
    }
}
