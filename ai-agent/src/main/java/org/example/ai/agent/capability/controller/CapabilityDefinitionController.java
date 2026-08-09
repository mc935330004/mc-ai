package org.example.ai.agent.capability.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.CapabilityBatchPublishDTO;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.dto.FieldDictionaryGenerateDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.capability.vo.*;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.security.CurrentUserProvider;
import org.example.ai.agent.security.PmPermissionResolver;
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
import org.example.ai.agent.capability.dto.CapabilityOptionQueryDTO;
import org.example.ai.agent.capability.ui.CapabilityOptionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.capability.ui.CapabilityFileUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final CurrentUserProvider currentUserProvider;
    /**
     * PM接口permission安全解析代理。
     */
    private final PmPermissionResolver pmPermissionResolver;
    /**
     * 通用WRITE远程选项服务。
     */
    private final CapabilityOptionService capabilityOptionService;
    /**
     * WRITE动态表单通用文件上传服务。
     */
    private final CapabilityFileUploadService capabilityFileUploadService;


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
        if (dto != null  && "WRITE".equalsIgnoreCase(dto.getSideEffect())) {
            pmPermissionResolver.verifyWriteConfiguration(dto.getMethod(),
                    dto.getUrl(),
                    dto.getInputSchemaJson(),
                    currentUserProvider
                            .getRequiredAuthorization()
            );
        }
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

        ToolExecutionContext context =ToolExecutionContext.builder()
                        .userId(currentUserProvider.getRequiredUserId())
                        .authorization(currentUserProvider.getRequiredAuthorization())
                        .variables(new LinkedHashMap<>())
                        .secureContext(new LinkedHashMap<>())
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

    /**
     * 查询WRITE动态表单字段的远程选项。
     *
     * 选项能力由WRITE字段Schema精确指定，
     * 前端不能直接传入任意OPTION_SOURCE能力编码。
     */
    @PostMapping("/{writeCapabilityCode}/fields/{fieldName}/options")
    public Result<List<CapabilityOptionVO>> queryFieldOptions(
            @PathVariable String writeCapabilityCode,
            @PathVariable String fieldName,
            @RequestBody(required = false)
            CapabilityOptionQueryDTO request) {

        Map<String, Object> form =request == null
                        || request.getForm() == null
                        ? Map.of()
                        : request.getForm();

        List<CapabilityOptionVO> options = capabilityOptionService.queryOptions(
                        writeCapabilityCode,
                        fieldName,
                        form,
                        currentUserProvider.getRequiredUserId(),
                        currentUserProvider.getRequiredAuthorization());

        return Result.success(options);
    }

    /**
     * 上传WRITE动态表单字段文件。
     *
     * 前端固定提交：
     * 1. file：文件内容；
     * 2. form：当前动态表单JSON。
     *
     * 上传能力编码只能从WRITE能力Schema读取，
     * 不允许前端通过请求参数指定。
     */
    @PostMapping(value = "/{writeCapabilityCode}/fields/{fieldPath}/upload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<CapabilityFileUploadVO> uploadFieldFile(
            @PathVariable String writeCapabilityCode,
            @PathVariable String fieldPath,
            @RequestPart("file") MultipartFile file,
            @RequestParam(
                    value = "form",
                    required = false
            )
            String formJson) {

        Map<String, Object> form = parseUploadForm(formJson);

        CapabilityFileUploadVO result =capabilityFileUploadService.upload(
                        writeCapabilityCode,
                        fieldPath,
                        file,
                        form,
                        currentUserProvider
                                .getRequiredUserId(),
                        currentUserProvider
                                .getRequiredAuthorization()
                );
        return Result.success(result);
    }
    /**
     * 调用真实 READ 接口并发现未配置字段。
     *
     * 当前接口只返回字段候选，不自动保存。
     */
    @PostMapping("/{capabilityCode}/sample")
    public Result<CapabilitySampleResultVO> sample(@PathVariable String capabilityCode, @RequestBody(required = false)
            CapabilityTestRequestDTO request) throws JsonProcessingException {
        PlanStep step = PlanStep.builder()
                .stepName("管理端READ能力样例调用")
                .capabilityCode(capabilityCode)
                .input(request == null ? new LinkedHashMap<>() : request.getInput())
                .outputKey("sampleResult")
                .build();
        ToolExecutionContext context =ToolExecutionContext.builder().userId(
                                currentUserProvider .getRequiredUserId()).authorization(
                                currentUserProvider.getRequiredAuthorization()).variables(new LinkedHashMap<>())
                        .build();
        ToolResult toolResult = businessCapabilityExecutor.executeReadTest(context,step);
        if (!toolResult.isSuccess()) {
            return Result.success(
                CapabilitySampleResultVO.builder()
                        .success(false)
                        .capabilityCode(capabilityCode)
                        .input(toInputMap(toolResult.getInput()))
                        .errorCode(toolResult.getErrorCode())
                        .errorMessage(toolResult.getErrorMessage())
                        .build()
            );
        }
        // 必须扫描 raw，而不是经过字段字典压缩后的 data。
        String rawJson = objectMapper.writeValueAsString(toolResult.getRaw());
        List<FieldDictionary> detected =fieldDictionaryService.detectFromJson(capabilityCode,rawJson);
        Set<String> existingPaths =fieldDictionaryService.lambdaQuery()
                        .eq(FieldDictionary::getCapabilityCode,capabilityCode)
                        .select(FieldDictionary::getFieldPath)
                        .list()
                        .stream()
                        .map(FieldDictionary::getFieldPath)
                        .collect(Collectors.toSet());

        List<FieldDictionary> newFields = detected.stream()
                .filter(field ->
                        !existingPaths.contains(field.getFieldPath()))
                .toList();
        return Result.success(
                CapabilitySampleResultVO.builder()
                        .success(true)
                        .capabilityCode(capabilityCode)
                        .input(toInputMap(toolResult.getInput()))
                        .rawData(toolResult.getRaw())
                        .newFields(newFields)
                        .build()
        );
    }

    /**
     * 审核并批量发布能力。
     */
    @PostMapping("/publish")
    public Result<CapabilityPublishResultVO> publish(@RequestBody CapabilityBatchPublishDTO dto) {
        return Result.success(capabilityDefinitionService.publishCapabilities(dto.getCapabilityCodes(),
                currentUserProvider.getRequiredUserId()));
    }

    /**
     * 查询业务接口对应的PM permission。
     *
     * 浏览器只访问Agent，Agent使用当前用户的Authorization调用PM。
     * 不返回PM原始响应和任何Token信息。
     */
    @PostMapping("/resolve-permission")
    public Result<Map<String, String>> resolvePermission(@RequestBody(required = false) Map<String, String> request) {
        String method =request == null ? null : request.get("method");
        String path =request == null
                        ? null
                        : request.get("path");
        String permission =pmPermissionResolver.resolve(
                        method,
                        path,
                        currentUserProvider.getRequiredAuthorization() );
        /*
         * 只向前端返回经过安全校验的permission。
         */
        return Result.success(Map.of("permission",permission));
    }


    /**
     * 将输入对象转换为输入映射。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toInputMap(Object input) {
        if (input instanceof Map<?, ?>) {
            return (Map<String, Object>) input;
        }
        return new LinkedHashMap<>();
    }
    /**
     * 解析文件上传时携带的当前表单数据。
     *
     * 只允许JSON对象，避免数组、字符串等错误数据
     * 进入能力参数绑定流程。
     */
    private Map<String, Object> parseUploadForm(
            String formJson) {

        if (!StringUtils.hasText(formJson)) {
            return new LinkedHashMap<>();
        }

        /*
         *  
         * form只用于补充上传能力的PATH、QUERY和普通BODY参数，
         * 不允许携带无限大的JSON字符串。
         */
        if (formJson.length() > 256 * 1024) {
            throw new BusinessException(
                    400,
                    "上传表单参数不能超过256KB"
            );
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(formJson);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "上传表单参数不是合法JSON"
            );
        }

        if (root == null || !root.isObject()) {
            throw new BusinessException(
                    400,
                    "上传表单参数必须是JSON对象"
            );
        }

        return objectMapper.convertValue(
                root,
                new TypeReference<
                        LinkedHashMap<String, Object>>() {
                }
        );
    }
}
