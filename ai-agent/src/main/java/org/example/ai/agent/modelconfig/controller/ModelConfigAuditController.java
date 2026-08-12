package org.example.ai.agent.modelconfig.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modelconfig.dto.ModelConfigAuditQueryDTO;
import org.example.ai.agent.modelconfig.entity.ModelConfigAuditLog;
import org.example.ai.agent.modelconfig.service.ModelConfigAuditService;
import org.example.ai.agent.modelconfig.vo.ModelConfigAuditVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型配置审计日志管理接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/model-config-audits")
public class ModelConfigAuditController {

    private final ModelConfigAuditService auditService;

    /**
     * 分页查询模型配置和授权变更审计日志。
     */
    @GetMapping
    public Result<Page<ModelConfigAuditVO>> page(Page<ModelConfigAuditLog> page,ModelConfigAuditQueryDTO query) {

        return Result.success(auditService.page(page, query));
    }
}