package org.example.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.extractor.DictionaryFactExtractor;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.invocation.runtime.*;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.BusinessSystemService;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
    private final BusinessSystemService businessSystemService;
    private final CapabilityInvocationContextFactory invocationContextFactory;
    private final CapabilityHttpRequestBuilder httpRequestBuilder;
    private final CapabilityHttpInvoker httpInvoker;
    private final CapabilityResponseInterpreter responseInterpreter;
    /**
     * 标准事实提取器。
     */
    private final DictionaryFactExtractor dictionaryFactExtractor;

    @Override
    public ToolResult execute(ToolExecutionContext context, PlanStep step) {
        // 普通工具执行入口只能调用 READ 能力
        return executeInternal( context,step,false,null, false);
    }

    @Override
    public ToolResult executeConfirmedWrite(ToolExecutionContext context, PlanStep step, String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return fail( step,context,"IDEMPOTENCY_KEY_REQUIRED", "写操作缺少幂等键");
        }
        return executeInternal(context,step,true, idempotencyKey,false);
    }

    @Override
    public ToolResult executeReadTest(ToolExecutionContext context, PlanStep step) {
        return executeInternal(context,step,false,null,true);
    }

    /**
     * 统一能力执行入口。
     */
    private ToolResult executeInternal(ToolExecutionContext context, PlanStep step, boolean confirmedWrite, String idempotencyKey,
                                       boolean adminReadTest) {
        String capabilityCode = step.getCapabilityCode();
        try {
            try{
            CapabilityDefinition capability;
            if (adminReadTest) {
                // 管理端允许读取尚未发布的草稿能力。
                capability = capabilityDefinitionService.lambdaQuery()
                        .eq(CapabilityDefinition::getCapabilityCode, capabilityCode).one();
            } else {
                // Agent 正常执行只能读取已启用、已发布能力。
                capability = capabilityDefinitionService.getEnabledByCode(capabilityCode);
            }

            if (capability == null) {
                return fail(step,safePublicInput(step),"CAPABILITY_NOT_FOUND",
                        "能力不存在或未启用：" + capabilityCode);
            }

            String sideEffect = capability.getSideEffect();
            // 危险能力当前始终禁止自动执行
            if ("DANGEROUS".equalsIgnoreCase(sideEffect)) {
                return fail(step,safePublicInput(step),"DANGEROUS_CAPABILITY_NOT_ALLOWED","危险能力禁止执行：" + capabilityCode);
            }
            // WRITE 能力必须经过待确认操作入口
            if ("WRITE".equalsIgnoreCase(sideEffect) && !confirmedWrite) {
                return fail(step,safePublicInput(step),"WRITE_CONFIRM_REQUIRED","写操作必须经过用户确认：" + capabilityCode);
            }
            // 防止错误配置绕过安全检查
            if (!"READ".equalsIgnoreCase(sideEffect) && !"WRITE".equalsIgnoreCase(sideEffect)) {
                return fail(step, safePublicInput(step),"INVALID_SIDE_EFFECT", "不支持的能力副作用类型：" + sideEffect);
            }
            CapabilityInvocationContext invocationContext = invocationContextFactory.create(context,
                            step);

            CapabilityHttpRequest httpRequest =httpRequestBuilder.build(
                            capability,
                            invocationContext,
                            idempotencyKey);
            Object raw = httpInvoker.invoke(httpRequest);

            /*
             * HTTP 2xx只表示传输成功。
             * 继续根据responseBindingJson判断业务是否成功。
             */
            ResponseInterpretationResult interpreted =responseInterpreter.interpret(
                            capability,
                            raw,
                            adminReadTest);

            if (!interpreted.success()) {
                return ToolResult.builder()
                        .success(false)
                        .capabilityCode(capabilityCode)
                        .outputKey(step.getOutputKey())
                        .businessCode(interpreted.businessCode())
                        .businessMessage(interpreted.businessMessage())
                        .errorCode(interpreted.errorCode())
                        .errorMessage(interpreted.errorMessage())
                        .summary("业务能力调用失败：" +interpreted.errorMessage())
                        .input(httpRequest.getAuditInput())
                        /*
                         * 管理端测试需要查看原始响应。
                         * 普通Agent运行不保存完整raw，
                         * 防止敏感业务数据写入运行轨迹。
                         */
                        .raw(adminReadTest ? raw : null)
                        .build();
            }
            List<FieldMeta> fields = loadFieldMetas(capabilityCode);
            /*
             * 没有字段字典时返回dataPath提取后的数据；
             * 配置字段字典后，继续按原始响应绝对路径压缩。
             */
            Object compactData =compactByFieldDictionary(raw,interpreted.data(),fields);

            List<AnswerFact> facts =dictionaryFactExtractor.extract(
                            capabilityCode,
                            raw,
                            fields);
            return ToolResult.builder()
                    .success(true)
                    .capabilityCode(capabilityCode)
                    .outputKey(step.getOutputKey())
                    .businessCode(interpreted.businessCode())
                    .businessMessage(interpreted.businessMessage() )
                    .emptyData(interpreted.emptyData())
                    .data(compactData)
                    .fields(fields)
                    .facts(facts)
                    .summary(interpreted.emptyData()
                                    ? "业务能力调用成功，但未查询到数据：" +
                                    capability.getCapabilityName()
                                    : "业务能力调用成功：" +
                                    capability.getCapabilityName())
                    /*
                     * 管理端测试保留raw，普通Agent不保留。
                     */
                    .raw(adminReadTest ? raw : null)
                    .input(httpRequest.getAuditInput())
                    .build();
                    } catch (CapabilityInvocationException exception) {
                        /*
                         * CapabilityInvocationException中的消息
                         * 必须保证不包含Token、Cookie和完整响应正文。
                         */
                        return fail(
                                step,
                                safePublicInput(step),
                                exception.getErrorCode(),
                                exception.getMessage()
                        );
                    }

        } catch (Exception e) {
            /*
             * 未知异常不能直接返回 exception.getMessage()，
             * 其中可能包含URL Query、Header或业务响应正文。
             */
            return fail(
                    step,
                    safePublicInput(step),
                    "BUSINESS_API_ERROR",
                    "业务能力调用失败"
            );
        }
    }
    private Map<String, Object> safePublicInput(PlanStep step) {
         if (step == null|| CollectionUtils.isEmpty(step.getInput())) {
            return Map.of();
        }
        return new LinkedHashMap<>(step.getInput());
    }

    /**
     * 加载字段语义字典。
     */
    private List<FieldMeta> loadFieldMetas(String capabilityCode) {
        List<FieldDictionary> dictionaries =  fieldDictionaryMapper.selectList(
                new LambdaQueryWrapper<FieldDictionary>()
                        .eq(FieldDictionary::getCapabilityCode,capabilityCode)
                        .eq(FieldDictionary::getPublishStatus,"PUBLISHED")
                        .orderByAsc( FieldDictionary::getDisplayOrder)
                        .orderByAsc( FieldDictionary::getId ) );

        return dictionaries.stream()
                .map(item -> FieldMeta.builder()
                        .name(item.getFieldName())
                        .cnName(item.getFieldCnName())
                        .path(item.getFieldPath())
                        .type(item.getFieldType())
                        .format(item.getDisplayFormat())
                        .meaning(item.getBusinessMeaning())
                        .requiredOutput(defaultInteger(item.getRequiredOutput(), 0))
                        .visible(defaultInteger(item.getVisible(),1))
                        .displayOrder(defaultInteger(item.getDisplayOrder(), 0))
                        .displayGroup(item.getDisplayGroup())
                        .nullDisplayText(StringUtils.hasText(item.getNullDisplayText())? item.getNullDisplayText()
                                        : "当前数据中未提供")
                        .build())
                .toList();
    }
    /**
     * Integer 空值默认处理。
     */
    private int defaultInteger(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
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
    private Object compactByFieldDictionary(Object raw,Object interpretedData, List<FieldMeta> fields) {
        /*
         * 没有字段字典时，必须返回dataPath提取的数据，
         * 不能再次返回完整raw。
         */
        if (fields == null || fields.isEmpty()) {
            return interpretedData;
        }
        if (raw == null) {
            return interpretedData;
        }

        JsonNode root =objectMapper.valueToTree(raw);

        ObjectNode result =objectMapper.createObjectNode();

        for (FieldMeta field : fields) {
            if (!StringUtils.hasText(field.getPath())) {
                continue;
            }

            if (field.getPath().contains("[]")) {
                compactArrayField(
                        root,
                        result,
                        field );
                continue;
            }

            JsonNode value =readBySimplePath(root, field.getPath());

            if (value == null || value.isMissingNode() || value.isNull()) {
                continue;
            }
            result.set(displayName(field),value);
        }

        return objectMapper.convertValue( result, Object.class );
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