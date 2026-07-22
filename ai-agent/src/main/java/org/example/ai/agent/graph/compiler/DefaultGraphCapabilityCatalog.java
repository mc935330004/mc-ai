package org.example.ai.agent.graph.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.RequestBindingSpecParser;
import org.example.ai.agent.capability.invocation.model.ParameterBindingSpec;
import org.example.ai.agent.capability.invocation.model.ParameterSourceType;
import org.example.ai.agent.capability.invocation.model.RequestBindingSpec;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * GraphSpec默认能力目录实现。
 *
 * 能力可调用状态和能力输入契约都来自已经发布的能力版本快照，
 * 不能读取主表中尚未发布的草稿配置。
 */
@Component
@RequiredArgsConstructor
public class DefaultGraphCapabilityCatalog
        implements GraphCapabilityCatalog {

    private static final String INPUT_ROOT = "$input";

    private final CapabilityDefinitionService
            capabilityDefinitionService;

    private final RequestBindingSpecParser
            requestBindingSpecParser;

    private final ObjectMapper objectMapper;

    /**
     * 当前阶段工作流只允许调用READ能力。
     *
     * WRITE能力后续需要人工确认、挂起恢复、
     * 幂等控制和完整审计，不能直接自动执行。
     */
    @Override
    public boolean isCallable(String capabilityCode) {
        if (!StringUtils.hasText(capabilityCode)) {
            return false;
        }

        CapabilityDefinition capability =
                capabilityDefinitionService
                        .getEnabledByCode(capabilityCode);

        return capability != null
                && "READ".equalsIgnoreCase(
                capability.getSideEffect()
        );
    }

    /**
     * 根据发布版本中的requestBindingJson生成工作流输入契约。
     *
     * 两种绑定形式：
     *
     * 1. $input.queryStr
     *    只开放queryStr字段。
     *
     * 2. $input
     *    从inputSchemaJson.properties展开全部公开字段，
     *    用于未来新增、修改等对象型能力。
     */
    @Override
    public Optional<GraphCapabilityContract>
    findContract(String capabilityCode) {

        if (!StringUtils.hasText(capabilityCode)) {
            return Optional.empty();
        }

        CapabilityDefinition capability =
                capabilityDefinitionService
                        .getEnabledByCode(capabilityCode);

        if (capability == null) {
            return Optional.empty();
        }

        RequestBindingSpec bindingSpec =
                requestBindingSpecParser.parse(
                        capability.getMethod(),
                        capability.getUrl(),
                        capability.getRequestBindingJson()
                );

        Set<String> allowedPaths =
                new LinkedHashSet<>();

        Set<String> requiredPaths =
                new LinkedHashSet<>();

        JsonNode inputSchema = null;

        for (ParameterBindingSpec parameter
                : bindingSpec.getParameters()) {

            if (parameter == null
                    || parameter.getSourceType()
                    != ParameterSourceType.INPUT) {
                continue;
            }

            String expression =
                    parameter.getSourceExpression() == null
                            ? ""
                            : parameter.getSourceExpression()
                            .trim();

            /*
             * 整个$input作为请求体时，
             * 从inputSchemaJson展开全部允许字段。
             */
            if (INPUT_ROOT.equals(expression)) {
                if (inputSchema == null) {
                    inputSchema =
                            parseInputSchema(capability);
                }

                collectSchemaPaths(
                        inputSchema,
                        "",
                        true,
                        allowedPaths,
                        requiredPaths
                );

                continue;
            }

            if (!expression.startsWith(
                    INPUT_ROOT + ".")) {
                /*
                 * 正常情况下RequestBindingSpecParser
                 * 已经拦截该错误，这里进行失败关闭。
                 */
                throw new IllegalStateException(
                        "能力INPUT绑定表达式不合法："
                                + capabilityCode
                );
            }

            String inputPath =
                    expression.substring(
                            (INPUT_ROOT + ".").length()
                    );

            allowedPaths.add(inputPath);

            if (parameter.isRequired()) {
                requiredPaths.add(inputPath);
            }
        }

        return Optional.of(
                new GraphCapabilityContract(
                        allowedPaths,
                        requiredPaths
                )
        );
    }

    /**
     * 读取能力输入Schema。
     */
    private JsonNode parseInputSchema(
            CapabilityDefinition capability) {

        if (!StringUtils.hasText(
                capability.getInputSchemaJson())) {

            throw new IllegalStateException(
                    "能力inputSchemaJson不能为空："
                            + capability.getCapabilityCode()
            );
        }

        try {
            JsonNode schema =
                    objectMapper.readTree(
                            capability.getInputSchemaJson()
                    );

            if (schema == null || !schema.isObject()) {
                throw new IllegalStateException(
                        "能力inputSchemaJson根节点必须是对象："
                                + capability.getCapabilityCode()
                );
            }

            return schema;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "能力inputSchemaJson解析失败："
                            + capability.getCapabilityCode(),
                    exception
            );
        }
    }

    /**
     * 递归展开Schema中的properties。
     *
     * allowedPaths同时包含对象父路径和叶子路径，例如：
     *
     * project
     * project.name
     * project.code
     *
     * 这样GraphSpec既可以配置整个project对象，
     * 也可以逐字段配置project.name。
     */
    private void collectSchemaPaths(
            JsonNode schema,
            String prefix,
            boolean parentRequired,
            Set<String> allowedPaths,
            Set<String> requiredPaths) {

        JsonNode properties =
                schema.path("properties");

        if (!properties.isObject()) {
            return;
        }

        Set<String> requiredNames =
                readRequiredNames(schema);

        properties.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldSchema = entry.getValue();

            String fieldPath =
                    StringUtils.hasText(prefix)
                            ? prefix + "." + fieldName
                            : fieldName;

            allowedPaths.add(fieldPath);

            /*
             * 只有父对象本身是必填时，
             * 子对象的required才是无条件必填。
             */
            boolean fieldRequired =
                    parentRequired
                            && requiredNames.contains(fieldName);

            if (fieldRequired) {
                requiredPaths.add(fieldPath);
            }

            /*
             * 数组字段只开放数组整体，
             * 不展开items内部字段。
             */
            if (!"array".equals(
                    fieldSchema.path("type")
                            .asText(""))) {

                collectSchemaPaths(
                        fieldSchema,
                        fieldPath,
                        fieldRequired,
                        allowedPaths,
                        requiredPaths
                );
            }
        });
    }

    /**
     * 读取一个对象Schema中的required字段。
     */
    private Set<String> readRequiredNames(
            JsonNode schema) {

        Set<String> requiredNames =
                new LinkedHashSet<>();

        JsonNode required =
                schema.path("required");

        if (!required.isArray()) {
            return requiredNames;
        }

        required.forEach(item -> {
            if (item.isTextual()
                    && StringUtils.hasText(
                    item.asText())) {

                requiredNames.add(
                        item.asText().trim()
                );
            }
        });

        return requiredNames;
    }
}