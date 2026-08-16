package org.example.ai.agent.modelconfig.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modelconfig.dto.ModelConfigSaveDTO;
import org.example.ai.agent.modelconfig.dto.ModelStatusDTO;
import org.example.ai.agent.modelconfig.service.ModelConfigService;
import org.example.ai.agent.modelconfig.service.ModelConnectivityTestService;
import org.example.ai.agent.modelconfig.vo.ModelConfigVO;
import org.example.ai.agent.modelconfig.vo.ModelTestResultVO;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大模型配置管理接口。
 *
 * 该路径属于现有Agent管理端权限范围，
 * 继续复用ai_agent_admin权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/admin/models")
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final ModelConnectivityTestService testService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public Result<List<ModelConfigVO>> list() {
        return Result.success(modelConfigService.list());
    }

    @PostMapping
    public Result<ModelConfigVO> create( @Valid @RequestBody ModelConfigSaveDTO dto) {
        String operator =currentUserProvider.getRequiredUserId();
        return Result.success(modelConfigService.create(dto, operator));
    }

    @PutMapping("/{modelCode}")
    public Result<ModelConfigVO> update(@PathVariable String modelCode, @Valid @RequestBody ModelConfigSaveDTO dto) {
        String operator = currentUserProvider.getRequiredUserId();
        return Result.success(modelConfigService.update(modelCode, dto, operator));
    }

    @PatchMapping("/{modelCode}/status")
    public Result<Void> updateStatus(@PathVariable String modelCode, @Valid @RequestBody ModelStatusDTO dto) {
        String operator =currentUserProvider.getRequiredUserId();
        modelConfigService.updateStatus(modelCode, dto.getEnabled(), dto.getVersion(), operator );
        return Result.success();
    }

    /**
     * 只测试指定模型，不进入自动故障转移链。
     */
    @PostMapping("/{modelCode}/test")
    public Result<ModelTestResultVO> test(@PathVariable String modelCode) {
        String operator =currentUserProvider.getRequiredUserId();
        return Result.success(testService.test(modelCode, operator));
    }
}