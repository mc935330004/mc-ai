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
        // 普通工具执行入口只能调用 READ 能力
        return executeInternal(context, step, false, null);

    }

    @Override
    public ToolResult executeConfirmedWrite(ToolExecutionContext context, PlanStep step, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail( step,context,"IDEMPOTENCY_KEY_REQUIRED", "写操作缺少幂等键");
        }
        return executeInternal(context, step, true, idempotencyKey);
    }
    /**
     * 统一能力执行入口。
     */
    private ToolResult executeInternal(ToolExecutionContext context, PlanStep step, boolean confirmedWrite, String idempotencyKey) {
        String capabilityCode = step.getCapabilityCode();
        try {
            CapabilityDefinition capability = capabilityDefinitionService.getEnabledByCode(capabilityCode);
            if (capability == null) {
                return fail(step, context, "CAPABILITY_NOT_FOUND", "能力不存在或未启用：" + capabilityCode);
            }
            String sideEffect = capability.getSideEffect();
            // 危险能力当前始终禁止自动执行
            if ("DANGEROUS".equalsIgnoreCase(sideEffect)) {
                return fail(step,context,"DANGEROUS_CAPABILITY_NOT_ALLOWED","危险能力禁止执行：" + capabilityCode);
            }
            // WRITE 能力必须经过待确认操作入口
            if ("WRITE".equalsIgnoreCase(sideEffect) && !confirmedWrite) {
                return fail(step,context,"WRITE_CONFIRM_REQUIRED","写操作必须经过用户确认：" + capabilityCode);
            }
            // 防止错误配置绕过安全检查
            if (!"READ".equalsIgnoreCase(sideEffect) && !"WRITE".equalsIgnoreCase(sideEffect)) {
                return fail(step, context,"INVALID_SIDE_EFFECT", "不支持的能力副作用类型：" + sideEffect);
            }
            Map<String, Object> requestParams = resolveInput(context, step);
            Object raw = invokeBusinessApi(capability, requestParams, idempotencyKey,context.getAuthorization());
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
    private Object invokeBusinessApi(CapabilityDefinition capability, Map<String, Object> params,
                                     String idempotencyKey,String authorization) {
        if (!StringUtils.hasText(authorization)) {
            throw new IllegalArgumentException("调用业务系统缺少 Authorization");
        }
        String url = baseUrl(capability.getUrl());
        String method = capability.getMethod();
        if (!StringUtils.hasText(method)) {
            throw new IllegalArgumentException("能力未配置请求方法：" + capability.getCapabilityCode());
        }
        if ("GET".equalsIgnoreCase(method)) {
            return restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(url);
                        // 没有查询参数时不会进行任何处理
                        if (!CollectionUtils.isEmpty(params)) {
                            params.forEach((name, value) -> {
                                // 空值不拼接到 URL，避免出现 name=null
                                if (value != null) {
                                    uriBuilder.queryParam(name, value);
                                }
                            });
                        }
                        return uriBuilder.build();
                    })
                    .header("Authorization", authorization)
                    .header("Idempotency-Key", idempotencyKey)
                    .retrieve()
                    .body(Object.class);
        }

        if ("POST".equalsIgnoreCase(method)) {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(url)
                    // 所有业务接口调用都必须携带当前用户认证信息
                    .header("Authorization", authorization);
            // 只有确认后的写操作才会携带幂等键
            if (StringUtils.hasText(idempotencyKey)) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            return request
                    .body(params == null ? Map.of() : params)
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
        if (arrayIndex < 0) {
            return;
        }
        String arrayPath = path.substring(0, arrayIndex);
        // 跳过 "[]."，得到 projectCode 这样的数组元素字段路径
        String leafPath = path.substring(arrayIndex + 3);

        JsonNode arrayNode = readBySimplePath(root, arrayPath);
        if (arrayNode == null || !arrayNode.isArray()) {
            return;
        }
        String arrayName = arrayPath.substring(arrayPath.lastIndexOf('.') + 1);
        ArrayNode targetArray = result.withArray(arrayName);
        for (int i = 0; i < arrayNode.size(); i++) {
            JsonNode sourceRow = arrayNode.get(i);
            if (sourceRow == null || !sourceRow.isObject()) {
                continue;
            }
            ObjectNode targetRow;
            // 同一条业务数据的多个字典字段要写入同一个对象
            if (targetArray.size() > i && targetArray.get(i).isObject()) {
                targetRow = (ObjectNode) targetArray.get(i);
            } else {
                targetRow = objectMapper.createObjectNode();
                targetArray.add(targetRow);
            }
            if (!StringUtils.hasText(leafPath)) {
                continue;
            }
            // readBySimplePath 要求路径以 $. 开头
            JsonNode value = readBySimplePath(sourceRow,"$." + leafPath);
            if (value == null|| value.isMissingNode()|| value.isNull()) {
                continue;
            }
            // 使用字段字典中的中文名称作为最终展示字段名
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