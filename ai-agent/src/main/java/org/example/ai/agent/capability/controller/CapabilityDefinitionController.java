package org.example.ai.agent.capability.controller;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 能力定义管理接口。
 *
 * 第一版主要用于手动录入和查看能力。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/capabilities")
public class CapabilityDefinitionController {

    /**
     * AI 能力定义服务。
     */
    private final CapabilityDefinitionService capabilityDefinitionService;

//    /**
//     * 查询全部能力列表。
//     */
//    @GetMapping
//    public Result<List<CapabilityDefinition>> list() {
//        return Result.success(capabilityDefinitionService.list());
//    }
//
//    /**
//     * 根据能力编码查询能力详情。
//     */
//    @GetMapping("/{capabilityCode}")
//    public Result<CapabilityDefinition> getByCode(@PathVariable String capabilityCode) {
//        return Result.success(capabilityDefinitionService.getEnabledByCode(capabilityCode));
//    }
//
//    /**
//     * 新增能力定义。
//     *
//     * 注意：
//     * 第一版可以先手动录入 READ 类型能力。
//     */
//    @PostMapping
//    public Result<Boolean> create(@RequestBody CapabilityDefinition capabilityDefinition) {
//        return Result.success(capabilityDefinitionService.save(capabilityDefinition));
//    }
//
//    /**
//     * 更新能力定义。
//     */
//    @PutMapping
//    public Result<Boolean> update(@RequestBody CapabilityDefinition capabilityDefinition) {
//        return Result.success(capabilityDefinitionService.updateById(capabilityDefinition));
//    }
//
//    /**
//     * 停用能力。
//     *
//     * 不建议物理删除能力，因为后续执行轨迹可能还需要关联历史能力编码。
//     */
//    @PostMapping("/{id}/disable")
//    public Result<Boolean> disable(@PathVariable Long id) {
//        CapabilityDefinition entity = new CapabilityDefinition();
//        entity.setId(id);
//        entity.setEnabled(0);
//        return Result.success(capabilityDefinitionService.updateById(entity));
//    }
}