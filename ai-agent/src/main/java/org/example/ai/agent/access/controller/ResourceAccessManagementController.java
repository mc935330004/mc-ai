package org.example.ai.agent.access.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.access.dto.ResourceAccessSaveDTO;
import org.example.ai.agent.access.service.ResourceAccessManagementService;
import org.example.ai.agent.access.vo.ResourceAccessVO;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力与工作流人员访问配置管理接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/resource-access")
public class ResourceAccessManagementController {

    private final ResourceAccessManagementService accessService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 查询指定能力或工作流的访问配置。
     */
    @GetMapping("/{resourceType}/{resourceId}")
    public Result<ResourceAccessVO> getAccess(@PathVariable String resourceType, @PathVariable Long resourceId) {
        return Result.success(accessService.getAccess(resourceType, resourceId));
    }

    /**
     * 保存指定能力或工作流的访问配置。
     */
    @PutMapping("/{resourceType}/{resourceId}")
    public Result<ResourceAccessVO> saveAccess(@PathVariable String resourceType,
                                               @PathVariable Long resourceId,
                                               @Valid @RequestBody ResourceAccessSaveDTO dto) {
        String operatorId = currentUserProvider.getRequiredUserId();
        return Result.success(accessService.saveAccess(resourceType, resourceId, dto, operatorId));
    }
}
