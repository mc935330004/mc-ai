package org.example.ai.agent.capability.invocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.invocation.model.ParameterBindingSpec;
import org.example.ai.agent.capability.invocation.model.ParameterSourceType;
import org.example.ai.agent.capability.invocation.model.RequestBindingSpec;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 能力请求输入契约校验器。
 *
 * 职责：
 * 1. 找出 requestBindingJson 中所有 INPUT 类型绑定；
 * 2. 校验 $input.xxx 是否存在于 inputSchemaJson；
 * 3. 支持嵌套对象路径；
 * 4. 阻止配置不存在的公开输入字段；
 * 5. 允许整个 $input 对象绑定，为后续新增、修改能力保留扩展能力。
 *
 * 本类只校验静态配置契约，不负责校验实际运行参数值。
 */
@Component
@RequiredArgsConstructor
public class CapabilityRequestContractValidator {

    private static final String INPUT_ROOT = "$input";

    private final ObjectMapper objectMapper;

    /**
     * 校验能力输入 Schema 和请求绑定是否一致。
     *
     * @param capabilityCode    能力编码，用于生成可定位的错误信息
     * @param inputSchemaJson   能力输入 JSON Schema
     * @param requestBindingSpec 已经通过基础语法校验的请求绑定
     */
    public void validate(
            String capabilityCode,
            String inputSchemaJson,
            RequestBindingSpec requestBindingSpec) {

        if (requestBindingSpec == null) {
            throw invalid(
                    capabilityCode,
                    "requestBindingJson解析结果不能为空"
            );
        }

        List<ParameterBindingSpec> parameters =
                requestBindingSpec.getParameters() == null
                        ? List.of()
                        : requestBindingSpec.getParameters();

        /*
         * 只有 INPUT 表示来自公开能力入参。
         *
         * FIXED、SECURE_CONTEXT、VARIABLE、ITEM
         * 不需要在 inputSchemaJson 中声明。
         */
        boolean containsInputBinding =
                parameters.stream()
                        .filter(parameter -> parameter != null)
                        .anyMatch(parameter ->
                                parameter.getSourceType()
                                        == ParameterSourceType.INPUT
                        );

        if (!containsInputBinding) {
            return;
        }

        JsonNode rootSchema = parseInputSchema(
                capabilityCode,
                inputSchemaJson
        );

        for (int index = 0;
             index < parameters.size();
             index++) {

            ParameterBindingSpec parameter =
                    parameters.get(index);

            if (parameter == null
                    || parameter.getSourceType()
                    != ParameterSourceType.INPUT) {
                continue;
            }

            validateInputExpression(
                    capabilityCode,
                    index,
                    parameter.getSourceExpression(),
                    rootSchema
            );
        }
    }

    /**
     * 解析并校验 inputSchemaJson 根节点。
     */
    private JsonNode parseInputSchema(
            String capabilityCode,
            String inputSchemaJson) {

        if (!StringUtils.hasText(inputSchemaJson)) {
            throw invalid(
                    capabilityCode,
                    "requestBindingJson使用了$input，"
                            + "但inputSchemaJson不能为空"
            );
        }

        JsonNode rootSchema;

        try {
            rootSchema =
                    objectMapper.readTree(inputSchemaJson);
        } catch (JsonProcessingException exception) {
            throw invalid(
                    capabilityCode,
                    "inputSchemaJson不是合法JSON"
            );
        }

        if (rootSchema == null
                || !rootSchema.isObject()) {
            throw invalid(
                    capabilityCode,
                    "inputSchemaJson根节点必须是JSON对象"
            );
        }

        /*
         * 兼容旧配置：
         * 没有显式配置 type，但存在 properties 时，
         * 仍然可以按照 object 类型处理。
         */
        String rootType =
                rootSchema.path("type").asText("");

        if (StringUtils.hasText(rootType)
                && !"object".equals(rootType)) {
            throw invalid(
                    capabilityCode,
                    "inputSchemaJson根节点type必须是object"
            );
        }

        return rootSchema;
    }

    /**
     * 校验单个 $input 来源表达式。
     */
    private void validateInputExpression(
            String capabilityCode,
            int parameterIndex,
            String sourceExpression,
            JsonNode rootSchema) {

        String normalizedExpression =
                StringUtils.hasText(sourceExpression)
                        ? sourceExpression.trim()
                        : "";

        /*
         * 整体绑定整个输入对象。
         *
         * 适用于：
         * sourceExpression = $input
         * targetLocation = BODY
         * targetPath = $
         *
         * 实际输入仍会经过 CapabilityInputSchemaValidator 清洗，
         * 不会绕过 Schema 白名单。
         */
        if (INPUT_ROOT.equals(normalizedExpression)) {
            return;
        }

        if (!normalizedExpression.startsWith(
                INPUT_ROOT + ".")) {

            throw invalid(
                    capabilityCode,
                    parameterPrefix(parameterIndex)
                            + "的INPUT表达式必须是$input"
                            + "或以$input.开头"
            );
        }

        String inputPath =
                normalizedExpression.substring(
                        (INPUT_ROOT + ".").length()
                );

        if (!StringUtils.hasText(inputPath)) {
            throw invalid(
                    capabilityCode,
                    parameterPrefix(parameterIndex)
                            + "没有指定具体输入字段"
            );
        }

        validateSchemaPath(
                capabilityCode,
                parameterIndex,
                inputPath,
                rootSchema
        );
    }

    /**
     * 按照 properties 逐级检查 Schema 路径。
     *
     * 示例：
     * project.name
     *
     * 对应：
     * properties.project.properties.name
     */
    private void validateSchemaPath(
            String capabilityCode,
            int parameterIndex,
            String inputPath,
            JsonNode rootSchema) {

        String[] pathParts =
                inputPath.split("\\.", -1);

        JsonNode currentSchema = rootSchema;
        StringBuilder checkedPath =
                new StringBuilder();

        for (String pathPart : pathParts) {
            if (!StringUtils.hasText(pathPart)) {
                throw invalid(
                        capabilityCode,
                        parameterPrefix(parameterIndex)
                                + "包含空路径片段："
                                + inputPath
                );
            }

            /*
             * 第一版不允许通过点路径直接读取数组内部元素。
             *
             * 数组字段可以整体绑定，例如：
             * $input.members
             *
             * 但不能写成：
             * $input.members.name
             */
            if ("array".equals(
                    currentSchema.path("type")
                            .asText(""))) {

                throw invalid(
                        capabilityCode,
                        parameterPrefix(parameterIndex)
                                + "不能通过点路径读取数组元素："
                                + inputPath
                                + "；请绑定整个数组字段"
                );
            }

            JsonNode properties =
                    currentSchema.path("properties");

            if (!properties.isObject()
                    || !properties.has(pathPart)) {

                String missingPath =
                        checkedPath.length() == 0
                                ? pathPart
                                : checkedPath + "." + pathPart;

                throw invalid(
                        capabilityCode,
                        parameterPrefix(parameterIndex)
                                + "引用了inputSchemaJson"
                                + "未声明字段："
                                + missingPath
                );
            }

            if (checkedPath.length() > 0) {
                checkedPath.append('.');
            }

            checkedPath.append(pathPart);

            currentSchema =
                    properties.get(pathPart);
        }
    }

    private String parameterPrefix(int parameterIndex) {
        return "requestBindingJson.parameters["
                + parameterIndex
                + "]";
    }

    /**
     * 创建统一的能力请求契约异常。
     */
    private BusinessException invalid(
            String capabilityCode,
            String message) {

        String safeCapabilityCode =
                StringUtils.hasText(capabilityCode)
                        ? capabilityCode.trim()
                        : "未命名能力";

        return new BusinessException(
                400,
                "能力请求契约无效【"
                        + safeCapabilityCode
                        + "】："
                        + message
        );
    }
}