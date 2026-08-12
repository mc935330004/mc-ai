package org.example.ai.agent.access.service;

import org.example.ai.agent.access.dto.ResourceAccessSaveDTO;
import org.example.ai.agent.access.vo.ResourceAccessVO;

/**
 * 可执行资源人员访问配置服务。
 */
public interface ResourceAccessManagementService {

    /**
     * 查询能力或工作流的当前访问配置。
     */
    ResourceAccessVO getAccess(
            String resourceType,
            Long resourceId
    );

    /**
     * 完整替换能力或工作流的当前访问配置。
     */
    ResourceAccessVO saveAccess(
            String resourceType,
            Long resourceId,
            ResourceAccessSaveDTO dto,
            String operatorId
    );
}
