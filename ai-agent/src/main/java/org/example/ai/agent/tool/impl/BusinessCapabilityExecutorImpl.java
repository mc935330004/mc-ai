package org.example.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * 业务能力执行器。
 *
 * 核心职责：
 * 1. 根据 capabilityCode 查询已启用能力定义。
 * 2. 合并计划入参和上游变量引用。
 * 3. 调用真实业务系统接口。
 * 4. 根据字段字典压缩返回数据，减少大模型噪声。
 */
@Service
@RequiredArgsConstructor
public class BusinessCapabilityExecutorImpl implements BusinessCapabilityExecutor {

    private final CapabilityDefinitionService capabilityDefinitionService;
    private final FieldDictionaryMapper fieldDictionaryMapper;
    private final BusinessApiProperties businessApiProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    @Override
    public ToolResult execute(ToolExecutionContext context, PlanStep step) {
        String capabilityCode = step.getCapabilityCode();
        try {
            CapabilityDefinition capability = capabilityDefinitionService.getEnabledByCode(capabilityCode);
            if (capability == null) {
                return fail(step, context, "CAPABILITY_NOT_FOUND", "能力不存在或未启用：" + capabilityCode);
            }

            // 当前阶段只允许 READ 能力，避免 Agent 自动执行写操作。
            if (!"READ".equalsIgnoreCase(capability.getSideEffect())) {
                return fail(step, context, "SIDE_EFFECT_NOT_ALLOWED", "当前版本只允许执行 READ 能力：" + capabilityCode);
            }

            Map<String, Object> requestParams = resolveInput(context, step);
            Object raw = invokeBusinessApi(capability, requestParams);
            List<FieldMeta> fields = loadFieldMetas(capabilityCode);
            Object compactData = compactByFieldDictionary(raw, fields);

            return ToolResult.builder()
                    .success(true)
                    .capabilityCode(capabilityCode)
                    .outputKey(step.getOutputKey())
                    .data(compactData)
                    .fields(fields)
                    .summary("业务能力调用成功：" + capability.getCapabilityName())
                    .raw(raw)
                    .input(requestParams)
                    .build();
        } catch (Exception e) {
            // 异常必须结构化返回，避免整个 Agent 链路直接中断。
            return fail(step, context, "BUSINESS_API_ERROR", e.getMessage());
        }
    }

    /**
     * 合并当前步骤的直接入参和上游变量引用。
     */
    private Map<String, Object> resolveInput(ToolExecutionContext context, PlanStep step) {
        Map<String, Object> params = new LinkedHashMap<>();

        if (!CollectionUtils.isEmpty(step.getInput())) {
            params.putAll(step.getInput());
        }

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
     * 当前只支持 $.变量名.字段名，例如 $.project.id。
     */
    private Object resolveVariable(ToolExecutionContext context, String expression) {
        if (context == null || context.getVariables() == null) {
            return null;
        }
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
     * 调用真实业务系统接口。
     */
    private Object invokeBusinessApi(CapabilityDefinition capability, Map<String, Object> params) {
        String url = baseUrl(capability.getUrl());
        String token = "2891c445-38f2-40b6-b3cd-a6c99dadba04";

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
     * 能力表保存绝对地址时直接使用；保存相对路径时，与 agent.business-api.base-url 拼接。
     */
    private String baseUrl(String capabilityUrl) {
        if (!StringUtils.hasText(capabilityUrl)) {
            throw new IllegalArgumentException("能力接口地址不能为空");
        }
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
        return baseUrl + capabilityUrl;
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
    public ToolResult fail(PlanStep step, Object input, String errorCode, String errorMessage) {
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

    /**
     * 根据字段字典压缩业务数据。
     *
     * raw 保留原始接口返回；data 只保留字段字典配置过的字段。
     */
    private Object compactByFieldDictionary(Object raw, List<FieldMeta> fields) {
        if (raw == null || fields == null || fields.isEmpty()) {
            return raw;
        }
        JsonNode root = objectMapper.valueToTree(raw);
        ObjectNode result = objectMapper.createObjectNode();
        for (FieldMeta field : fields) {
            if (!StringUtils.hasText(field.getPath())) {
                continue;
            }
            if (field.getPath().contains("[]")) {
                compactArrayField(root, result, field);
                continue;
            }
            JsonNode value = readBySimplePath(root, field.getPath());
            if (value == null || value.isMissingNode() || value.isNull()) {
                continue;
            }
            result.set(displayName(field), value);
        }
        return objectMapper.convertValue(result, Object.class);
    }

    /**
     * 根据字段路径读取 JSON 值。
     *
     * 当前支持 $.data.xxx 和 $.data.records[].xxx 这类常见路径。
     */
    private JsonNode readBySimplePath(JsonNode root, String path) {
        if (root == null || !StringUtils.hasText(path) || !path.startsWith("$.")) {
            return null;
        }
        String[] parts = path.substring(2).split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isMissingNode() || current.isNull()) {
                return null;
            }
            current = current.path(part);
        }
        return current;
    }

    /**
     * 压缩数组字段，例如 $.data.records[].contractAmount。
     */
    private void compactArrayField(JsonNode root, ObjectNode result, FieldMeta field) {
        String path = field.getPath();
        int arrayIndex = path.indexOf("[]");
        String arrayPath = path.substring(0, arrayIndex);
        String leafPath = path.substring(arrayIndex + 3);

        JsonNode arrayNode = readBySimplePath(root, arrayPath);
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        String arrayName = arrayPath.substring(arrayPath.lastIndexOf('.') + 1);
        ArrayNode targetArray = result.withArray(arrayName);
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode row = arrayNode.get(i);
            ObjectNode targetRow;

            if (targetArray.size() > i && targetArray.get(i).isObject()) {
                targetRow = (ObjectNode) targetArray.get(i);
            } else {
                targetRow = objectMapper.createObjectNode();
                targetArray.add(targetRow);
            }

            if (!StringUtils.hasText(leafPath)) {
                continue;
            }
            JsonNode value = readBySimplePath(row, "$" + leafPath);
            if (value == null || value.isMissingNode() || value.isNull()) {
                continue;
            }
            targetRow.set(displayName(field), value);
        }
    }

    /**
     * 获取字段展示名：优先使用字段字典中文名，没有中文名时回退到英文名。
     */
    private String displayName(FieldMeta field) {
        if (field == null) {
            return "";
        }
        if (StringUtils.hasText(field.getCnName())) {
            return field.getCnName();
        }
        if (StringUtils.hasText(field.getName())) {
            return field.getName();
        }
        return field.getPath();
    }
}