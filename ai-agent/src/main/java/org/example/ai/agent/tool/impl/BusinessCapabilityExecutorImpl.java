package org.example.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.common.config.BusinessApiProperties;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务能力执行器实现。
 *
 * 核心流程：
 * 1. 根据 capabilityCode 查询能力定义
 * 2. 校验能力是否启用、是否只读
 * 3. 合并 step.input 和 step.inputRef 参数
 * 4. 调用真实业务接口
 * 5. 查询字段字典并统一包装 ToolResult
 */
@Service
@RequiredArgsConstructor
public class BusinessCapabilityExecutorImpl implements BusinessCapabilityExecutor {
    private final CapabilityDefinitionService capabilityDefinitionService;
    private final FieldDictionaryMapper fieldDictionaryMapper;
    private final BusinessApiProperties businessApiProperties;
    private final RestClient restClient;
    @Override
    public ToolResult execute(ToolExecutionContext context, PlanStep step) {
        // 1. 根据 capabilityCode 查询能力定义
        String capabilityCode = step.getCapabilityCode();
        try{
            // 1. 根据能力编码查询已启用能力。
            CapabilityDefinition capability = capabilityDefinitionService.getEnabledByCode(capabilityCode);
            if (capability == null) {
                return fail(step, context,"CAPABILITY_NOT_FOUND", "能力不存在或未启用：" + capabilityCode);
            }
            // 2. 第一版只允许 READ，避免 Agent 自动执行写操作。
            if (!"READ".equalsIgnoreCase(capability.getSideEffect())) {
                return fail(step, context,"SIDE_EFFECT_NOT_ALLOWED", "当前版本只允许执行 READ 能力：" + capabilityCode);
            }
            // 3. 解析当前步骤入参，包括直接 input 和 inputRef。
            Map<String, Object> requestParams = resolveInput(context, step);
            // 4. 调用真实业务接口。
            Object raw = invokeBusinessApi(capability, requestParams);
            // 5. 查询字段语义字典，方便后续 AnswerComposer 使用。
            List<FieldMeta> fields = loadFieldMetas(capabilityCode);
            // 6. 包装统一返回结构。
            return ToolResult.builder()
                    .success(true)
                    .capabilityCode(capabilityCode)
                    .outputKey(step.getOutputKey())
                    .data(raw)
                    .fields(fields)
                    .summary("业务能力调用成功：" + capability.getCapabilityName())
                    .raw(raw)
                    .input(requestParams)
                    .build();
        }catch (Exception e){
            // 7. 异常必须结构化返回，不能让整个 Agent 链路直接崩掉。
            return fail(step, context,"BUSINESS_API_ERROR", e.getMessage());
        }

    }

    /**
     * 合并当前步骤输入参数。
     */
    private Map<String, Object> resolveInput(ToolExecutionContext context, PlanStep step) {
        Map<String, Object> params = new LinkedHashMap<>();

        // 直接入参，例如 {"projectName": "A项目"}。
        if (!CollectionUtils.isEmpty(step.getInput())) {
            params.putAll(step.getInput());
        }

        // 引用上游结果，例如 {"projectId": "$.project.id"}。
        if (!CollectionUtils.isEmpty(step.getInputRef())) {
            step.getInputRef().forEach((paramName, expression) ->
                    params.put(paramName, resolveVariable(context, expression))
            );
        }

        return params;
    }

    /**
     * 解析简单变量表达式。
     *
     * 第一版只支持 $.变量名.字段名 这种格式。
     * 示例：
     * $.project.id -> context.variables["project"]["id"]
     */
    private Object resolveVariable(ToolExecutionContext context, String expression) {
        if (!StringUtils.hasText(expression) || !expression.startsWith("$.")) {
            return null;
        }
        String[] parts = expression.substring(2).split("\\.");
        Object current = context.getVariables().get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(parts[i]);
            } else {
                return null;
            }
        }
        return current;
    }
    /**
     * 调用真实业务接口。
     */
    private Object invokeBusinessApi(CapabilityDefinition capability, Map<String, Object> params) {
        String url = baseUrl(capability.getUrl());
        String token="2891c445-38f2-40b6-b3cd-a6c99dadba04";
//        params.put("queryStr", "2674033");
        // GET 请求使用 query param。
        if ("GET".equalsIgnoreCase(capability.getMethod())) {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(url);
                        params.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(Object.class);
        }
        // POST 请求使用 JSON body。
        if ("POST".equalsIgnoreCase(capability.getMethod())) {
            return restClient.post()
                    .uri(url)
                    .body(params)
                    .retrieve()
                    .body(Object.class);
        }
        throw new IllegalArgumentException("不支持的请求方法：" + capability.getMethod());
    }
    /**
     * 拼接业务接口地址。
     *
     * 如果能力表里保存的是 /api/projects 这种相对路径，
     * 就和 agent.business-api.base-url 拼成完整地址。
     */
    private String baseUrl(String capabilityUrl) {
        if (capabilityUrl.startsWith("http://") || capabilityUrl.startsWith("https://")) {
            return capabilityUrl;
        }
        String baseUrl = businessApiProperties.getBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("agent.business-api.base-url 未配置，无法调用相对路径能力：" + capabilityUrl);
        }

        if (baseUrl.endsWith("/") && capabilityUrl.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + capabilityUrl;
        }
        if (!baseUrl.endsWith("/") && !capabilityUrl.startsWith("/")) {
            return baseUrl + "/" + capabilityUrl;
        }
        return capabilityUrl;
    }

    /**
     * 加载字段语义字典。
     */
    private List<FieldMeta> loadFieldMetas(String capabilityCode) {
        List<FieldDictionary> dictionaries = fieldDictionaryMapper.selectList(
                new LambdaQueryWrapper<FieldDictionary>()
                        .eq(FieldDictionary::getCapabilityCode, capabilityCode)
        );
        return dictionaries.stream()
                .map(item -> FieldMeta.builder()
                        .name(item.getFieldName())
                        .cnName(item.getFieldCnName())
                        .path(item.getFieldPath())
                        .type(item.getFieldType())
                        .format(item.getDisplayFormat())
                        .meaning(item.getBusinessMeaning())
                        .build())
                .toList();
    }

    /**
     * 构建失败结果。
     */
    public ToolResult fail(PlanStep step, Object input,String errorCode, String errorMessage) {
        return ToolResult.builder()
                .success(false)
                .capabilityCode(step.getCapabilityCode())
                .outputKey(step.getOutputKey())
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .summary("业务能力调用失败：" + errorMessage)
                .input(input)
                .build();
    }
}
