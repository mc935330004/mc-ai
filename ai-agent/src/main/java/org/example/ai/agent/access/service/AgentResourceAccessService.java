package org.example.ai.agent.access.service;

import org.example.ai.agent.access.model.ExecutableResourceType;

import java.util.Collection;
import java.util.Set;

/**
 * 能力和工作流统一运行权限服务。
 */
public interface AgentResourceAccessService {

    /**
     * 判断当前人员是否可以运行指定资源。
     */
    boolean canAccess(
            ExecutableResourceType resourceType,
            Long resourceId,
            String userId);

    /**
     * 强制校验当前人员的资源运行权限，无权限时抛出403业务异常。
     */
    void requireAccess(
            ExecutableResourceType resourceType,
            Long resourceId,
            String userId);

    /**
     * 批量筛选当前人员可运行的资源ID，供候选召回阶段使用。
     */
    Set<Long> filterAccessibleResourceIds(
            ExecutableResourceType resourceType,
            Collection<Long> resourceIds,
            String userId);

    /**
     * 按能力编码强制校验当前人员的运行权限。
     */
    void requireCapabilityAccess(
            String capabilityCode,
            String userId);

    /**
     * 按工作流编码强制校验当前人员的运行权限。
     */
    void requireWorkflowAccess(
            String workflowCode,
            String userId);
}
