package org.example.ai.agent.capability.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.dto.FieldDictionaryGenerateDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.capability.vo.CapabilityDetailVO;
import org.example.ai.agent.capability.vo.CapabilityTestResultVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final BusinessCapabilityExecutor businessCapabilityExecutor;
    private final FieldDictionaryService fieldDictionaryService;
    private final ObjectMapper objectMapper;
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
                                               @RequestBody(required = false) CapabilityTestRequestDTO request) throws JsonProcessingException {
        if (!StringUtils.hasText(capabilityCode)) {
            throw new BusinessException(400, "能力编码不能为空");
        }
        // 构造一个最小 PlanStep，复用正式 Agent 的能力执行器。
        // ponytail: 不另写一套 HTTP 调用逻辑，测试路径和真实执行路径保持一致。
        PlanStep step = PlanStep.builder()
                .stepName("管理端测试调用")
                .capabilityCode(capabilityCode)
                .input(request == null ? new LinkedHashMap<>() : request.getInput())
                .outputKey("testResult")
                .build();
        ToolExecutionContext context = ToolExecutionContext.builder()
                .variables(new LinkedHashMap<>())
                .build();
        ToolResult result = businessCapabilityExecutor.execute(context, step);
        FieldDictionaryGenerateDTO dto = FieldDictionaryGenerateDTO.builder()
                .capabilityCode(capabilityCode)
                .json(objectMapper.writeValueAsString(result.getData()))
                .build();
        fieldDictionaryService.generateFromJson(dto);
        return Result.success(CapabilityTestResultVO.builder()
                .success(result.isSuccess())
                .capabilityCode(result.getCapabilityCode())
                .input((Map<String, Object>) result.getInput())
                .data(result.getData())
                .fields(result.getFields())
                .summary(result.getSummary())
                .errorCode(result.getErrorCode())
                .errorMessage(result.getErrorMessage())
                .build());
    }

    /**
     * 查询能力详情，包含字段字典。
     *
     * 管理端编辑能力时用这个接口，避免前端调两次。
     */
    @GetMapping("/detailWithFields/{id}")
    public Result<CapabilityDetailVO> detailWithFields(@PathVariable Long id) {
        return Result.success(capabilityDefinitionService.detailWithFields(id));
    }
}