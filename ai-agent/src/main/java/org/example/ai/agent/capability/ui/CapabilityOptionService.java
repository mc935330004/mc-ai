package org.example.ai.agent.capability.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.runtime.SimpleJsonPathReader;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.vo.CapabilityOptionResolution;
import org.example.ai.agent.capability.vo.CapabilityOptionVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.StepType;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通用WRITE远程选项服务。
 *
 * 职责：
 * 1. 根据WRITE Schema精确找到OPTION_SOURCE能力；
 * 2. 调用现有READ能力执行器；
 * 3. 统一输出label/value；
 * 4. 将中文名称安全转换成真实ID。
 */
@Service
@RequiredArgsConstructor
public class CapabilityOptionService {

    private final CapabilityDefinitionService capabilityDefinitionService;

    private final BusinessCapabilityExecutor businessCapabilityExecutor;

    private final ObjectMapper objectMapper;

    private final SimpleJsonPathReader jsonPathReader;

    private final CapabilityUiSchemaParser uiSchemaParser;

    /**
     * 查询某个WRITE字段的远程选项。
     */
    public List<CapabilityOptionVO> queryOptions(
            String writeCapabilityCode,
            String fieldName,
            Map<String, Object> form,
            String userId,
            String authorization) {

        requireText(
                writeCapabilityCode,
                "WRITE能力编码不能为空"
        );
        requireText(
                fieldName,
                "字段名称不能为空"
        );
        requireText(
                userId,
                "当前用户不能为空"
        );
        requireText(
                authorization,
                "当前请求缺少Authorization"
        );

        CapabilityDefinition writeCapability =
                getRequiredCapability(
                        writeCapabilityCode
                );

        if (!"WRITE".equalsIgnoreCase(
                writeCapability.getSideEffect()
        )) {
            throw badRequest(
                    "目标能力不是WRITE："
                            + writeCapabilityCode
            );
        }

        CapabilityUiSchemaParser.UiSchema writeSchema =
                uiSchemaParser.parse(
                        writeCapability
                                .getInputSchemaJson()
                );

        CapabilityUiSchemaParser.Field field =
                writeSchema.fields().get(fieldName);

        if (field == null) {
            throw badRequest(
                    "WRITE能力未声明字段："
                            + fieldName
            );
        }

        if (!"REMOTE_SELECT".equals(
                field.component()
        ) || field.optionSource() == null) {
            throw badRequest(
                    "字段不是远程下拉类型："
                            + fieldName
            );
        }

        CapabilityUiSchemaParser.OptionSource source =
                field.optionSource();

        CapabilityDefinition optionCapability =
                getRequiredCapability(
                        source.capabilityCode()
                );

        if (!"READ".equalsIgnoreCase(
                optionCapability.getSideEffect()
        )) {
            throw badRequest(
                    "选项能力必须是READ："
                            + source.capabilityCode()
            );
        }

        if (!uiSchemaParser.isOptionSource(
                optionCapability.getInputSchemaJson()
        )) {
            throw badRequest(
                    "能力未声明为OPTION_SOURCE："
                            + source.capabilityCode()
            );
        }

        Map<String, Object> optionInput =
                buildOptionInput(
                        source,
                        form == null
                                ? Map.of()
                                : form
                );

        PlanStep optionStep =
                PlanStep.builder()
                        .stepType(
                                StepType.BUSINESS_TOOL
                        )
                        .stepName(
                                "查询动态表单选项"
                        )
                        .capabilityCode(
                                source.capabilityCode()
                        )
                        .input(optionInput)
                        .outputKey("options")
                        .build();

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .runId(
                                "option_"
                                        + UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                        )
                        .userId(userId)
                        .authorization(authorization)
                        .variables(
                                new LinkedHashMap<>()
                        )
                        .userContext(
                                new LinkedHashMap<>()
                        )
                        .secureContext(
                                new LinkedHashMap<>()
                        )
                        .build();

        /*
         * 这里只调用普通READ入口。
         * OPTION_SOURCE绝不允许调用WRITE执行入口。
         */
        ToolResult toolResult =
                businessCapabilityExecutor.execute(
                        context,
                        optionStep
                );

        if (toolResult == null
                || !toolResult.isSuccess()) {
            String errorMessage =
                    toolResult == null
                            ? "选项能力没有返回结果"
                            : toolResult.getErrorMessage();

            throw new BusinessException(
                    502,
                    StringUtils.hasText(errorMessage)
                            ? errorMessage
                            : "远程选项查询失败"
            );
        }

        return readOptions(
                toolResult.getWorkflowData(),
                source,
                fieldName
        );
    }

    /**
     * 将用户输入的中文名称或ID转换为业务接口需要的真实值。
     */
    public CapabilityOptionResolution resolveInput(
            String writeCapabilityCode,
            Map<String, Object> rawInput,
            String userId,
            String authorization) {

        CapabilityDefinition writeCapability =
                getRequiredCapability(
                        writeCapabilityCode
                );

        if (!"WRITE".equalsIgnoreCase(
                writeCapability.getSideEffect()
        )) {
            throw badRequest(
                    "目标能力不是WRITE："
                            + writeCapabilityCode
            );
        }

        CapabilityUiSchemaParser.UiSchema schema =
                uiSchemaParser.parse(
                        writeCapability
                                .getInputSchemaJson()
                );

        Map<String, Object> input =
                rawInput == null
                        ? Map.of()
                        : rawInput;

        Map<String, Object> requestInput =
                new LinkedHashMap<>();

        Map<String, Object> displayInput =
                new LinkedHashMap<>();

        List<CapabilityUiSchemaParser.Field>
                remoteFields =
                new ArrayList<>();

        /*
         * 先复制普通字段。
         *
         * 未在Schema中声明的字段不会进入最终请求。
         */
        for (CapabilityUiSchemaParser.Field field :
                schema.fields().values()) {

            if ("REMOTE_SELECT".equals(
                    field.component()
            )) {
                remoteFields.add(field);
                continue;
            }

            if (input.containsKey(field.name())) {
                Object value =
                        input.get(field.name());

                requestInput.put(
                        field.name(),
                        value
                );
                displayInput.put(
                        field.name(),
                        value
                );
            }
        }

        /*
         * 按依赖关系解析远程字段。
         *
         * 不依赖properties配置顺序，
         * 父级下拉会先转换成ID，再查询子级下拉。
         */
        while (!remoteFields.isEmpty()) {
            Set<String> pendingNames =
                    new LinkedHashSet<>();

            for (CapabilityUiSchemaParser.Field field :
                    remoteFields) {
                pendingNames.add(field.name());
            }

            CapabilityUiSchemaParser.Field current =
                    remoteFields.stream()
                            .filter(field ->
                                    field.dependsOn()
                                            .stream()
                                            .noneMatch(
                                                    pendingNames::contains
                                            )
                            )
                            .findFirst()
                            .orElse(null);

            if (current == null) {
                return clarify(
                        requestInput,
                        displayInput,
                        "远程下拉字段存在循环依赖，请检查x-ui.dependsOn配置"
                );
            }

            Object rawValue =
                    input.get(current.name());

            /*
             * 未填写的字段先跳过。
             * 最后统一根据required生成追问。
             */
            if (!isMissing(rawValue)) {
                for (String dependency :
                        current.dependsOn()) {

                    if (isMissing(
                            requestInput.get(dependency)
                    )) {
                        CapabilityUiSchemaParser.Field
                                dependencyField =
                                schema.fields()
                                        .get(dependency);

                        String dependencyLabel =
                                dependencyField == null
                                        ? dependency
                                        : dependencyField.label();

                        return clarify(
                                requestInput,
                                displayInput,
                                "请先提供【"
                                        + dependencyLabel
                                        + "】"
                        );
                    }
                }

                List<CapabilityOptionVO> options =
                        queryOptions(
                                writeCapabilityCode,
                                current.name(),
                                requestInput,
                                userId,
                                authorization
                        );

                CapabilityOptionResolution matched =
                        matchOption(
                                current,
                                rawValue,
                                options,
                                requestInput,
                                displayInput
                        );

                if (!matched.isReady()) {
                    return matched;
                }
            }

            remoteFields.remove(current);
        }

        /*
         * 远程字段解析完成后检查必填项。
         */
        for (String requiredField :
                schema.requiredFields()) {

            if (isMissing(
                    requestInput.get(requiredField)
            )) {
                CapabilityUiSchemaParser.Field field =
                        schema.fields()
                                .get(requiredField);

                return clarify(
                        requestInput,
                        displayInput,
                        "请提供必填字段【"
                                + (
                                field == null
                                        ? requiredField
                                        : field.label()
                        )
                                + "】"
                );
            }
        }

        return CapabilityOptionResolution.builder()
                .ready(true)
                .requestInput(requestInput)
                .displayInput(displayInput)
                .build();
    }

    /**
     * 根据Schema inputMapping构造OPTION_SOURCE能力入参。
     */
    private Map<String, Object> buildOptionInput(
            CapabilityUiSchemaParser.OptionSource source,
            Map<String, Object> form) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        for (Map.Entry<String, String> mapping :
                source.inputMapping().entrySet()) {

            String expression =
                    mapping.getValue();

            String sourceField =
                    expression.substring(
                            "$form.".length()
                    );

            Object value =
                    form.get(sourceField);

            if (isMissing(value)) {
                throw badRequest(
                        "请先提供远程选项依赖字段："
                                + sourceField
                );
            }

            result.put(
                    mapping.getKey(),
                    value
            );
        }

        return result;
    }

    /**
     * 从安全的workflowData中读取选项。
     */
    private List<CapabilityOptionVO> readOptions(
            Object workflowData,
            CapabilityUiSchemaParser.OptionSource source,
            String fieldName) {

        JsonNode root =
                objectMapper.valueToTree(
                        workflowData
                );

        SimpleJsonPathReader.ReadResult readResult =
                jsonPathReader.read(
                        root,
                        source.itemsPath()
                );

        if (!readResult.found()
                || readResult.value() == null
                || !readResult.value().isArray()) {
            throw badRequest(
                    "选项能力返回路径不是数组："
                            + fieldName
                            + "，itemsPath="
                            + source.itemsPath()
            );
        }

        Map<String, CapabilityOptionVO> unique =
                new LinkedHashMap<>();

        for (JsonNode item : readResult.value()) {
            if (!item.isObject()) {
                continue;
            }

            JsonNode valueNode =
                    item.get(source.valueField());

            JsonNode labelNode =
                    item.get(source.labelField());

            if (valueNode == null
                    || valueNode.isNull()
                    || labelNode == null
                    || labelNode.isNull()) {
                continue;
            }

            String label =
                    labelNode.asText("").trim();

            if (!StringUtils.hasText(label)) {
                continue;
            }

            Object value =
                    objectMapper.convertValue(
                            valueNode,
                            Object.class
                    );

            /*
             * 按真实值去重，保持业务接口原始顺序。
             */
            unique.putIfAbsent(
                    valueNode.toString(),
                    CapabilityOptionVO.builder()
                            .value(value)
                            .label(label)
                            .build()
            );
        }

        return List.copyOf(
                unique.values()
        );
    }

    /**
     * 匹配用户输入的ID或中文名称。
     */
    private CapabilityOptionResolution matchOption(
            CapabilityUiSchemaParser.Field field,
            Object rawValue,
            List<CapabilityOptionVO> options,
            Map<String, Object> requestInput,
            Map<String, Object> displayInput) {

        List<CapabilityOptionVO> valueMatches =
                options.stream()
                        .filter(option ->
                                sameValue(
                                        option.getValue(),
                                        rawValue
                                )
                        )
                        .toList();

        if (valueMatches.size() == 1) {
            return accepted(
                    field,
                    valueMatches.get(0),
                    requestInput,
                    displayInput
            );
        }

        String textValue =
                String.valueOf(rawValue).trim();

        List<CapabilityOptionVO> labelMatches =
                options.stream()
                        .filter(option ->
                                option.getLabel()
                                        .equals(textValue)
                        )
                        .toList();

        if (labelMatches.size() == 1) {
            return accepted(
                    field,
                    labelMatches.get(0),
                    requestInput,
                    displayInput
            );
        }

        if (labelMatches.size() > 1) {
            String candidates =
                    labelMatches.stream()
                            .map(option ->
                                    option.getLabel()
                                            + "（"
                                            + option.getValue()
                                            + "）"
                            )
                            .reduce(
                                    (left, right) ->
                                            left + "、" + right
                            )
                            .orElse("");

            return clarify(
                    requestInput,
                    displayInput,
                    "字段【"
                            + field.label()
                            + "】存在多条匹配："
                            + candidates
                            + "，请选择具体记录"
            );
        }

        return clarify(
                requestInput,
                displayInput,
                "字段【"
                        + field.label()
                        + "】的值【"
                        + textValue
                        + "】不在当前可选范围"
        );
    }

    private CapabilityOptionResolution accepted(
            CapabilityUiSchemaParser.Field field,
            CapabilityOptionVO option,
            Map<String, Object> requestInput,
            Map<String, Object> displayInput) {

        requestInput.put(
                field.name(),
                option.getValue()
        );

        displayInput.put(
                field.name(),
                option.getLabel()
        );

        return CapabilityOptionResolution.builder()
                .ready(true)
                .requestInput(requestInput)
                .displayInput(displayInput)
                .build();
    }

    private CapabilityOptionResolution clarify(
            Map<String, Object> requestInput,
            Map<String, Object> displayInput,
            String question) {

        return CapabilityOptionResolution.builder()
                .ready(false)
                .requestInput(
                        new LinkedHashMap<>(
                                requestInput
                        )
                )
                .displayInput(
                        new LinkedHashMap<>(
                                displayInput
                        )
                )
                .clarifyQuestion(question)
                .build();
    }

    private CapabilityDefinition getRequiredCapability(
            String capabilityCode) {

        CapabilityDefinition capability =
                capabilityDefinitionService
                        .getEnabledByCode(
                                capabilityCode
                        );

        if (capability == null) {
            throw new BusinessException(
                    404,
                    "能力不存在、未启用或未发布："
                            + capabilityCode
            );
        }

        return capability;
    }

    private boolean sameValue(
            Object optionValue,
            Object inputValue) {

        if (optionValue == null
                || inputValue == null) {
            return false;
        }

        return String.valueOf(optionValue)
                .trim()
                .equals(
                        String.valueOf(inputValue)
                                .trim()
                );
    }

    private boolean isMissing(Object value) {
        return value == null
                || (
                value instanceof String text
                        && !StringUtils.hasText(text)
        );
    }

    private void requireText(
            String value,
            String message) {

        if (!StringUtils.hasText(value)) {
            throw badRequest(message);
        }
    }

    private BusinessException badRequest(
            String message) {
        return new BusinessException(
                400,
                message
        );
    }
}