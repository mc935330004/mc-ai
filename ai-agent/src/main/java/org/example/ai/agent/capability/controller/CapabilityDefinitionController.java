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
import org.example.ai.agent.capability.vo.AgentCapabilityVO;
import org.example.ai.agent.capability.vo.CapabilityDetailVO;
import org.example.ai.agent.capability.vo.CapabilityTestResultVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力定义管理接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/capabilities")
public class CapabilityDefinitionController {

    private final CapabilityDefinitionService capabilityDefinitionService;
    private final BusinessCapabilityExecutor businessCapabilityExecutor;
    private final FieldDictionaryService fieldDictionaryService;
    private final ObjectMapper objectMapper;
    /**
     * 分页查询能力列表。
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
     */
    @PostMapping("/{id}/disable")
    public Result<Boolean> disable(@PathVariable Long id) {
        return Result.success(capabilityDefinitionService.updateEnabled(id, 0));
    }

    /**
     * 测试调用能力。
     *
     * 只测试真实接口调用，不自动生成或保存字段字典。
     */
    @PostMapping("/{capabilityCode}/test")
    public Result<CapabilityTestResultVO> test(@PathVariable String capabilityCode,
                                               @RequestBody(required = false) CapabilityTestRequestDTO request) throws JsonProcessingException {
        if (!StringUtils.hasText(capabilityCode)) {
            throw new BusinessException(400, "能力编码不能为空");
        }

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
        fieldDictionaryService.generateFromJson(FieldDictionaryGenerateDTO.builder()
                .capabilityCode(result.getCapabilityCode())
                .json(objectMapper.writeValueAsString(result.getData()))
                .build());
        return Result.success(CapabilityTestResultVO.builder()
                .success(result.isSuccess())
                .capabilityCode(result.getCapabilityCode())
                .input(toInputMap(result.getInput()))
                .data(result.getData())
                .fields(result.getFields())
                .summary(result.getSummary())
                .errorCode(result.getErrorCode())
                .errorMessage(result.getErrorMessage())
                .build());
    }

    /**
     * 查询能力详情，包含字段字典。
     */
    @GetMapping("/detailWithFields/{id}")
    public Result<CapabilityDetailVO> detailWithFields(@PathVariable Long id) {
        return Result.success(capabilityDefinitionService.detailWithFields(id));
    }

    /**
     * 查询 Agent 可用能力清单。
     */
    @GetMapping("/agent/list")
    public Result<List<AgentCapabilityVO>> agentList() {
        return Result.success(capabilityDefinitionService.listEnabledForAgent());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toInputMap(Object input) {
        if (input instanceof Map<?, ?>) {
            return (Map<String, Object>) input;
        }
        return new LinkedHashMap<>();
    }
}
