package org.example.ai.agent.capability.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.vo.CapabilityTestResultVO;
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

    /**
     * 查询全部能力列表。
     */
    @GetMapping("/pageList")
    public Result<?> pageList(Page<CapabilityDefinition> page,
                              @RequestParam(value = "keyword", required = false) String keyword,
                              @RequestParam(value = "domain", required = false) String domain,
                              @RequestParam(value = "enabled", required = false) Integer enabled) {
        return Result.success(capabilityDefinitionService.pageCapabilities(page, keyword, domain, enabled));
    }
    /**
     * 查询能力详情。
     */
    @GetMapping("/detail/{id}")
    public Result<CapabilityDefinition> detail(@PathVariable Long id) {
        return Result.success(capabilityDefinitionService.getById(id));
    }

    /**
     * 新增或修改能力。
     * 新增：id 为空。
     * 修改：id 必传。
     */
    @PostMapping("/save")
    public Result<Boolean> save(@RequestBody CapabilitySaveDTO dto) {
        return Result.success(capabilityDefinitionService.saveCapability(dto));
    }

    /**
     * 启用能力。
     */
    @PostMapping("/{id}/enable")
    public Result<Boolean> enable(@PathVariable Long id) {
        return Result.success(capabilityDefinitionService.updateEnabled(id, 1));
    }

    /**
     * 停用能力。
     *
     * 不做物理删除，因为执行轨迹后续还要关联 capabilityCode。
     */
    @PostMapping("/{id}/disable")
    public Result<Boolean> disable(@PathVariable Long id) {
        return Result.success(capabilityDefinitionService.updateEnabled(id, 0));
    }

    /**
     * 测试调用能力。
     *
     * 示例：
     */
    @PostMapping("/{capabilityCode}/test")
    public Result<?> test(@PathVariable String capabilityCode,
                                               @RequestBody(required = false) CapabilityTestRequestDTO request) {
        return Result.success(capabilityDefinitionService.testCapability(capabilityCode, request));
    }
}