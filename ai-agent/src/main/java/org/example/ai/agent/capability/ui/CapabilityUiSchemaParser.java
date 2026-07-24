package org.example.ai.agent.capability.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用能力动态表单 Schema 解析器。
 *
 * 只识别通用协议，不包含任何具体业务字段判断。
 */
@Component
@RequiredArgsConstructor
public class CapabilityUiSchemaParser {

    /**
     * 当前前端支持的组件类型。
     */
    private static final Set<String> SUPPORTED_COMPONENTS =
            Set.of(
                    "INPUT",
                    "NUMBER",
                    "DATE",
                    "RADIO",
                    "REMOTE_SELECT"
            );

    /**
     * 输入映射只允许读取当前表单字段。
     */
    private static final Pattern FORM_EXPRESSION =
            Pattern.compile(
                    "^\\$form\\.([A-Za-z_][A-Za-z0-9_]*)$"
            );

    private final ObjectMapper objectMapper;

    /**
     * 解析并校验 inputSchemaJson。
     */
    public UiSchema parse(String schemaJson) {
        JsonNode root = readRoot(schemaJson);

        validateCapabilityRole(root);

        JsonNode propertiesNode =root.get("properties");

        /*
         * 兼容以前使用 {} 的能力配置。
         * 没有 properties 时表示没有动态表单字段。
         */
        if (propertiesNode == null || propertiesNode.isNull()) {

            if (root.has("required")) {
                throw badRequest(
                        "配置required时必须同时配置properties"
                );
            }

            return new UiSchema(
                    Map.of(),
                    Set.of()
            );
        }

        if (!propertiesNode.isObject()) {
            throw badRequest(
                    "inputSchemaJson.properties必须是JSON对象"
            );
        }

        Set<String> propertyNames =
                new LinkedHashSet<>();

        propertiesNode.fieldNames()
                .forEachRemaining(
                        propertyNames::add
                );

        Set<String> requiredFields =
                readRequiredFields(
                        root.get("required"),
                        propertyNames
                );

        Map<String, Field> fields =
                new LinkedHashMap<>();

        Iterator<Map.Entry<String, JsonNode>> iterator =
                propertiesNode.fields();

        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry =
                    iterator.next();

            String fieldName = entry.getKey();
            JsonNode fieldSchema = entry.getValue();

            Field field = parseField(
                    fieldName,
                    fieldSchema,
                    propertyNames
            );

            fields.put(fieldName, field);
        }

        return new UiSchema(
                Collections.unmodifiableMap(fields),
                Collections.unmodifiableSet(
                        requiredFields
                )
        );
    }

    /**
     * 判断能力是否为远程选项数据源。
     */
    public boolean isOptionSource(String schemaJson) {
        JsonNode root = readRoot(schemaJson);

        return "OPTION_SOURCE".equalsIgnoreCase(
                root.path("x-capability-role")
                        .asText()
        );
    }

    /**
     * 解析单个字段。
     */
    private Field parseField(
            String fieldName,
            JsonNode fieldSchema,
            Set<String> propertyNames) {

        if (!fieldSchema.isObject()) {
            throw badRequest(
                    "字段Schema必须是JSON对象："
                            + fieldName
            );
        }

        JsonNode uiNode =
                fieldSchema.get("x-ui");

        if (uiNode != null
                && !uiNode.isObject()) {
            throw badRequest(
                    "字段x-ui必须是JSON对象："
                            + fieldName
            );
        }

        String type = fieldSchema
                .path("type")
                .asText("");

        String component;

        if (uiNode == null) {
            component = defaultComponent(type);
        } else {
            component = text(
                    uiNode,
                    "component"
            ).toUpperCase();

            if (!StringUtils.hasText(component)) {
                throw badRequest(
                        "字段x-ui.component不能为空："
                                + fieldName
                );
            }
        }

        if (component != null
                && !SUPPORTED_COMPONENTS.contains(
                        component
                )) {
            throw badRequest(
                    "不支持的动态表单组件："
                            + component
            );
        }

        List<String> dependsOn =
                readDependsOn(
                        fieldName,
                        uiNode == null
                                ? null
                                : uiNode.get("dependsOn"),
                        propertyNames
                );

        OptionSource optionSource = null;

        if ("REMOTE_SELECT".equals(component)) {
            optionSource = readOptionSource(
                    fieldName,
                    uiNode,
                    propertyNames,
                    dependsOn
            );
        } else if (uiNode != null
                && uiNode.has("optionSource")) {
            throw badRequest(
                    "只有REMOTE_SELECT可以配置optionSource："
                            + fieldName
            );
        }

        String label = text(
                uiNode,
                "label"
        );

        if (!StringUtils.hasText(label)) {
            label = text(
                    fieldSchema,
                    "description"
            );
        }

        if (!StringUtils.hasText(label)) {
            label = text(
                    fieldSchema,
                    "title"
            );
        }

        if (!StringUtils.hasText(label)) {
            label = fieldName;
        }

        return new Field(
                fieldName,
                label,
                type,
                component,
                dependsOn,
                optionSource
        );
    }

    /**
     * 解析远程下拉选项配置。
     */
    private OptionSource readOptionSource(
            String fieldName,
            JsonNode uiNode,
            Set<String> propertyNames,
            List<String> dependsOn) {

        if (uiNode == null
                || !uiNode.path("optionSource")
                .isObject()) {
            throw badRequest(
                    "REMOTE_SELECT必须配置optionSource："
                            + fieldName
            );
        }

        JsonNode sourceNode =
                uiNode.get("optionSource");

        Map<String, String> inputMapping =
                new LinkedHashMap<>();

        JsonNode mappingNode =
                sourceNode.get("inputMapping");

        if (mappingNode != null
                && !mappingNode.isObject()) {
            throw badRequest(
                    "inputMapping必须是JSON对象："
                            + fieldName
            );
        }

        if (mappingNode != null) {
            Iterator<Map.Entry<String, JsonNode>>
                    mappingIterator =
                    mappingNode.fields();

            while (mappingIterator.hasNext()) {
                Map.Entry<String, JsonNode> mapping =
                        mappingIterator.next();

                String expression = mapping
                        .getValue()
                        .asText("")
                        .trim();

                Matcher matcher =
                        FORM_EXPRESSION.matcher(
                                expression
                        );

                if (!matcher.matches()) {
                    throw badRequest(
                            "inputMapping只允许使用$form.<字段>："
                                    + fieldName
                    );
                }

                String sourceField =
                        matcher.group(1);

                if (!propertyNames.contains(sourceField)) {
                    throw badRequest(
                            "inputMapping引用了未声明字段："
                                    + sourceField
                    );
                }

                if (!dependsOn.contains(sourceField)) {
                    throw badRequest(
                            "inputMapping引用字段必须同时配置dependsOn："
                                    + sourceField
                    );
                }

                inputMapping.put(
                        mapping.getKey(),
                        expression
                );
            }
        }

        return new OptionSource(
                requiredText(
                        sourceNode,
                        "capabilityCode",
                        fieldName
                ),
                requiredText(
                        sourceNode,
                        "itemsPath",
                        fieldName
                ),
                requiredText(
                        sourceNode,
                        "labelField",
                        fieldName
                ),
                requiredText(
                        sourceNode,
                        "valueField",
                        fieldName
                ),
                Collections.unmodifiableMap(
                        inputMapping
                )
        );
    }

    /**
     * 读取字段依赖。
     */
    private List<String> readDependsOn(
            String fieldName,
            JsonNode dependsNode,
            Set<String> propertyNames) {

        if (dependsNode == null
                || dependsNode.isNull()) {
            return List.of();
        }

        if (!dependsNode.isArray()) {
            throw badRequest(
                    "dependsOn必须是字符串数组："
                            + fieldName
            );
        }

        List<String> result =
                new ArrayList<>();

        for (JsonNode item : dependsNode) {
            String dependency =
                    item.asText("").trim();

            if (!StringUtils.hasText(dependency)) {
                throw badRequest(
                        "dependsOn不能包含空字段："
                                + fieldName
                );
            }

            if (fieldName.equals(dependency)) {
                throw badRequest(
                        "字段不能依赖自身："
                                + fieldName
                );
            }

            if (!propertyNames.contains(dependency)) {
                throw badRequest(
                        "字段" + fieldName
                                + "依赖了未声明字段："
                                + dependency
                );
            }

            if (!result.contains(dependency)) {
                result.add(dependency);
            }
        }

        return List.copyOf(result);
    }

    /**
     * 读取必填字段。
     */
    private Set<String> readRequiredFields(
            JsonNode requiredNode,
            Set<String> propertyNames) {

        Set<String> result =
                new LinkedHashSet<>();

        if (requiredNode == null
                || requiredNode.isNull()) {
            return result;
        }

        if (!requiredNode.isArray()) {
            throw badRequest(
                    "inputSchemaJson.required必须是字符串数组"
            );
        }

        for (JsonNode item : requiredNode) {
            String fieldName =
                    item.asText("").trim();

            if (!StringUtils.hasText(fieldName)
                    || !propertyNames.contains(fieldName)) {
                throw badRequest(
                        "required引用了未声明字段："
                                + fieldName
                );
            }

            result.add(fieldName);
        }

        return result;
    }

    /**
     * 校验根节点能力角色。
     */
    private void validateCapabilityRole(
            JsonNode root) {

        JsonNode roleNode =
                root.get("x-capability-role");

        if (roleNode == null
                || roleNode.isNull()) {
            return;
        }

        String role =
                roleNode.asText("").trim();

        if (!"OPTION_SOURCE".equalsIgnoreCase(role)) {
            throw badRequest(
                    "不支持的能力角色：" + role
            );
        }
    }

    /**
     * 读取 JSON 根节点。
     */
    private JsonNode readRoot(String schemaJson) {
        if (!StringUtils.hasText(schemaJson)) {
            throw badRequest(
                    "inputSchemaJson不能为空"
            );
        }

        try {
            JsonNode root =
                    objectMapper.readTree(schemaJson);

            if (root == null || !root.isObject()) {
                throw badRequest(
                        "inputSchemaJson必须是JSON对象"
                );
            }

            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw badRequest(
                    "inputSchemaJson不是合法JSON"
            );
        }
    }

    private String requiredText(
            JsonNode node,
            String key,
            String fieldName) {

        String value = text(node, key);

        if (!StringUtils.hasText(value)) {
            throw badRequest(
                    "字段" + fieldName
                            + "的optionSource."
                            + key
                            + "不能为空"
            );
        }

        return value;
    }

    private String text(
            JsonNode node,
            String key) {

        return node == null
                ? ""
                : node.path(key)
                .asText("")
                .trim();
    }

    /**
     * 未配置x-ui时，根据基础类型提供默认控件。
     */
    private String defaultComponent(String type) {
        return switch (type) {
            case "integer", "number" -> "NUMBER";
            case "boolean" -> "RADIO";
            case "string" -> "INPUT";
            default -> null;
        };
    }

    private BusinessException badRequest(
            String message) {
        return new BusinessException(
                400,
                message
        );
    }

    /**
     * 解析后的通用表单。
     */
    public record UiSchema(
            Map<String, Field> fields,
            Set<String> requiredFields) {
    }

    /**
     * 通用表单字段。
     */
    public record Field(
            String name,
            String label,
            String type,
            String component,
            List<String> dependsOn,
            OptionSource optionSource) {
    }

    /**
     * 远程下拉选项配置。
     */
    public record OptionSource(
            String capabilityCode,
            String itemsPath,
            String labelField,
            String valueField,
            Map<String, String> inputMapping) {
    }
}