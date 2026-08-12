package org.example.ai.agent.access.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.access.entity.ResourceUserGrant;
import org.example.ai.agent.access.mapper.ResourceUserGrantMapper;
import org.example.ai.agent.access.model.ExecutableResourceType;
import org.example.ai.agent.access.model.ResourceAccessScope;
import org.example.ai.agent.access.service.AgentResourceAccessService;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.mapper.WorkflowDefinitionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 能力和工作流统一运行权限服务实现。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentResourceAccessServiceImpl
        implements AgentResourceAccessService {

    private final ResourceUserGrantMapper grantMapper;
    private final CapabilityDefinitionMapper capabilityMapper;
    private final WorkflowDefinitionMapper workflowMapper;

    /**
     * 单资源权限判断使用当前定义表策略，不读取发布快照。
     */
    @Override
    public boolean canAccess(
            ExecutableResourceType resourceType,
            Long resourceId,
            String userId) {

        String normalizedUserId = normalizeUserId(userId);
        ResourcePolicy policy = loadPolicy(resourceType, resourceId);

        return isAccessible(
                policy,
                resourceType,
                normalizedUserId
        );
    }

    /**
     * 执行入口必须调用该方法，防止绕过候选过滤直接运行资源。
     */
    @Override
    public void requireAccess(
            ExecutableResourceType resourceType,
            Long resourceId,
            String userId) {

        if (!canAccess(resourceType, resourceId, userId)) {
            logAccessDenied(
                    resourceType,
                    resourceId,
                    userId
            );
            throw accessDenied();
        }
    }

    /**
     * 一次加载资源策略和当前人员授权，避免候选列表逐条查询数据库。
     */
    @Override
    public Set<Long> filterAccessibleResourceIds(
            ExecutableResourceType resourceType,
            Collection<Long> resourceIds,
            String userId) {

        String normalizedUserId = normalizeUserId(userId);
        LinkedHashSet<Long> normalizedIds =
                normalizeResourceIds(resourceIds);

        if (normalizedIds.isEmpty()) {
            return Set.of();
        }

        Map<Long, ResourcePolicy> policies =
                loadPolicies(resourceType, normalizedIds);
        LinkedHashSet<Long> restrictedIds =
                findRestrictedIds(policies);
        Set<Long> grantedIds = loadGrantedIds(
                resourceType,
                restrictedIds,
                normalizedUserId
        );
        LinkedHashSet<Long> accessibleIds =
                new LinkedHashSet<>();

        for (Long resourceId : normalizedIds) {
            ResourcePolicy policy = policies.get(resourceId);

            if (policy == null) {
                continue;
            }

            if (policy.scope() == ResourceAccessScope.PUBLIC
                    || grantedIds.contains(resourceId)) {
                accessibleIds.add(resourceId);
            }
        }

        return accessibleIds;
    }

    /**
     * 按稳定能力编码读取主定义后校验，避免运行快照缺少权限字段。
     */
    @Override
    public void requireCapabilityAccess(
            String capabilityCode,
            String userId) {

        String normalizedCode = normalizeResourceCode(
                capabilityCode,
                "能力编码不能为空"
        );
        CapabilityDefinition definition =
                capabilityMapper.selectOne(
                        Wrappers.<CapabilityDefinition>lambdaQuery()
                                .select(
                                        CapabilityDefinition::getId,
                                        CapabilityDefinition::getAccessScope
                                )
                                .eq(
                                        CapabilityDefinition::getCapabilityCode,
                                        normalizedCode
                                )
                                .last("LIMIT 1")
                );

        if (definition == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "能力不存在"
            );
        }

        requireLoadedPolicy(
                ExecutableResourceType.CAPABILITY,
                definition.getId(),
                definition.getAccessScope(),
                userId
        );
    }

    /**
     * 按稳定工作流编码读取主定义后校验，重试和指定版本执行仍使用当前策略。
     */
    @Override
    public void requireWorkflowAccess(
            String workflowCode,
            String userId) {

        String normalizedCode = normalizeResourceCode(
                workflowCode,
                "工作流编码不能为空"
        );
        WorkflowDefinition definition =
                workflowMapper.selectOne(
                        Wrappers.<WorkflowDefinition>lambdaQuery()
                                .select(
                                        WorkflowDefinition::getId,
                                        WorkflowDefinition::getAccessScope
                                )
                                .eq(
                                        WorkflowDefinition::getWorkflowCode,
                                        normalizedCode
                                )
                                .last("LIMIT 1")
                );

        if (definition == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "工作流不存在"
            );
        }

        requireLoadedPolicy(
                ExecutableResourceType.WORKFLOW,
                definition.getId(),
                definition.getAccessScope(),
                userId
        );
    }

    /**
     * 使用已经读取的主定义策略完成强制校验，减少一次重复查询。
     */
    private void requireLoadedPolicy(
            ExecutableResourceType resourceType,
            Long resourceId,
            String accessScope,
            String userId) {

        String normalizedUserId = normalizeUserId(userId);
        ResourcePolicy policy = new ResourcePolicy(
                resourceId,
                parseStoredScope(accessScope)
        );

        if (!isAccessible(
                policy,
                resourceType,
                normalizedUserId
        )) {
            logAccessDenied(
                    resourceType,
                    resourceId,
                    normalizedUserId
            );
            throw accessDenied();
        }
    }

    /**
     * 统一判断公开策略和指定人员策略。
     */
    private boolean isAccessible(
            ResourcePolicy policy,
            ExecutableResourceType resourceType,
            String userId) {

        if (policy.scope() == ResourceAccessScope.PUBLIC) {
            return true;
        }

        Long grantCount = grantMapper.selectCount(
                Wrappers.<ResourceUserGrant>lambdaQuery()
                        .eq(
                                ResourceUserGrant::getResourceType,
                                resourceType.name()
                        )
                        .eq(
                                ResourceUserGrant::getResourceId,
                                policy.resourceId()
                        )
                        .eq(
                                ResourceUserGrant::getUserId,
                                userId
                        )
        );

        return grantCount != null && grantCount > 0;
    }

    /**
     * 加载一个资源的当前访问策略。
     */
    private ResourcePolicy loadPolicy(
            ExecutableResourceType resourceType,
            Long resourceId) {

        validateResourceId(resourceId);
        Map<Long, ResourcePolicy> policies =
                loadPolicies(resourceType, List.of(resourceId));
        ResourcePolicy policy = policies.get(resourceId);

        if (policy == null) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "可执行资源不存在"
            );
        }

        return policy;
    }

    /**
     * 按资源类型批量加载当前主定义策略。
     */
    private Map<Long, ResourcePolicy> loadPolicies(
            ExecutableResourceType resourceType,
            Collection<Long> resourceIds) {

        if (resourceType == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "资源类型不能为空"
            );
        }

        Map<Long, ResourcePolicy> policies =
                new LinkedHashMap<>();

        if (resourceType == ExecutableResourceType.CAPABILITY) {
            List<CapabilityDefinition> definitions =
                    capabilityMapper.selectBatchIds(resourceIds);

            for (CapabilityDefinition definition : definitions) {
                policies.put(
                        definition.getId(),
                        new ResourcePolicy(
                                definition.getId(),
                                parseStoredScope(
                                        definition.getAccessScope()
                                )
                        )
                );
            }
        } else {
            List<WorkflowDefinition> definitions =
                    workflowMapper.selectBatchIds(resourceIds);

            for (WorkflowDefinition definition : definitions) {
                policies.put(
                        definition.getId(),
                        new ResourcePolicy(
                                definition.getId(),
                                parseStoredScope(
                                        definition.getAccessScope()
                                )
                        )
                );
            }
        }

        return policies;
    }

    /**
     * 提取需要查询人员授权的受限资源ID。
     */
    private LinkedHashSet<Long> findRestrictedIds(
            Map<Long, ResourcePolicy> policies) {

        LinkedHashSet<Long> restrictedIds =
                new LinkedHashSet<>();

        for (ResourcePolicy policy : policies.values()) {
            if (policy.scope() == ResourceAccessScope.RESTRICTED) {
                restrictedIds.add(policy.resourceId());
            }
        }

        return restrictedIds;
    }

    /**
     * 批量读取当前人员已经获得授权的资源ID。
     */
    private Set<Long> loadGrantedIds(
            ExecutableResourceType resourceType,
            Collection<Long> restrictedIds,
            String userId) {

        if (restrictedIds.isEmpty()) {
            return Set.of();
        }

        LinkedHashSet<Long> grantedIds =
                new LinkedHashSet<>();
        List<ResourceUserGrant> grants = grantMapper.selectList(
                Wrappers.<ResourceUserGrant>lambdaQuery()
                        .select(ResourceUserGrant::getResourceId)
                        .eq(
                                ResourceUserGrant::getResourceType,
                                resourceType.name()
                        )
                        .eq(ResourceUserGrant::getUserId, userId)
                        .in(
                                ResourceUserGrant::getResourceId,
                                restrictedIds
                        )
        );

        for (ResourceUserGrant grant : grants) {
            grantedIds.add(grant.getResourceId());
        }

        return grantedIds;
    }

    /**
     * 兼容升级前空值，其他非法数据库值直接暴露配置错误。
     */
    private ResourceAccessScope parseStoredScope(
            String accessScope) {

        if (!StringUtils.hasText(accessScope)) {
            return ResourceAccessScope.PUBLIC;
        }

        try {
            return ResourceAccessScope.valueOf(
                    accessScope.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "数据库中的资源访问范围无效：" + accessScope,
                    exception
            );
        }
    }

    /**
     * 清理并校验登录人员编码，运行权限不接受匿名身份。
     */
    private String normalizeUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED,
                    "当前登录用户不能为空"
            );
        }

        String normalizedUserId = userId.trim();

        if (normalizedUserId.length() > 64) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "当前登录用户编码不能超过64个字符"
            );
        }

        return normalizedUserId;
    }

    /**
     * 清理批量资源ID并保持输入顺序。
     */
    private LinkedHashSet<Long> normalizeResourceIds(
            Collection<Long> resourceIds) {

        LinkedHashSet<Long> normalizedIds =
                new LinkedHashSet<>();

        if (resourceIds == null) {
            return normalizedIds;
        }

        for (Long resourceId : resourceIds) {
            if (resourceId != null && resourceId > 0) {
                normalizedIds.add(resourceId);
            }
        }

        return normalizedIds;
    }

    /**
     * 校验单资源ID。
     */
    private void validateResourceId(Long resourceId) {
        if (resourceId == null || resourceId <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "资源ID必须大于0"
            );
        }
    }

    /**
     * 清理稳定资源编码。
     */
    private String normalizeResourceCode(
            String resourceCode,
            String emptyMessage) {

        if (!StringUtils.hasText(resourceCode)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    emptyMessage
            );
        }

        return resourceCode.trim();
    }

    /**
     * 不暴露授权名单和资源策略细节。
     */
    private BusinessException accessDenied() {
        return new BusinessException(
                ErrorCode.FORBIDDEN,
                "当前用户无权运行该资源"
        );
    }

    /**
     * 权限拒绝日志只记录判定所需标识，不记录名单、认证信息和业务数据。
     */
    private void logAccessDenied(
            ExecutableResourceType resourceType,
            Long resourceId,
            String userId) {

        log.warn(
                "可执行资源访问被拒绝，userId={}, resourceType={}, resourceId={}",
                userId,
                resourceType == null
                        ? null
                        : resourceType.name(),
                resourceId
        );
    }

    /**
     * 统一承载资源ID和当前访问范围。
     */
    private record ResourcePolicy(
            Long resourceId,
            ResourceAccessScope scope) {
    }
}
