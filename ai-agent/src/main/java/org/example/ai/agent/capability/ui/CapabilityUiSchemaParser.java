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
 * 只解析通用 WRITE 能力表单协议，
 * 不包含新增立项、对方单位等具体业务字段判断。
 */
@Component
@RequiredArgsConstructor
public class CapabilityUiSchemaParser {

    /**
     * OBJECT_LIST 允许配置的最大行数。
     */
    private static final int OBJECT_LIST_HARD_LIMIT = 50;

    /**
     * 未配置 maxItems 时的默认最大行数。
     */
    private static final int OBJECT_LIST_DEFAULT_MAX_ITEMS = 20;
    /**
     * 当前前端支持的通用表单组件。
     */
    private static final Set<String> SUPPORTED_COMPONENTS =
            Set.of(
                    "INPUT",
                    "NUMBER",
                    "DATE",
                    "RADIO",
                    "SELECT",
                    "REMOTE_SELECT",
                    "FILE_UPLOAD",
                    "OBJECT_LIST"
            );

    /**
     * 远程下拉输入映射只允许读取当前表单字段。
     */
    private static final Pattern FORM_EXPRESSION =
            Pattern.compile(
                    "^\\$form\\.([A-Za-z_][A-Za-z0-9_]*)$"
            );

    private final ObjectMapper objectMapper;

    /**
     * 解析并校验能力 inputSchemaJson。
     */
    public UiSchema parse(String schemaJson) {
        JsonNode root = readRoot(schemaJson);

        validateCapabilityRole(root);

        JsonNode propertiesNode =
                root.get("properties");

        /*
         * 兼容以前使用空对象 {} 的能力。
         * 没有 properties 表示没有动态表单字段。
         */
        if (propertiesNode == null
                || propertiesNode.isNull()) {

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
                readPropertyNames(propertiesNode);

        Set<String> requiredFields =
                readRequiredFields(
                        root.get("required"),
                        propertyNames,
                        "inputSchemaJson"
                );

        Map<String, Field> fields =
                new LinkedHashMap<>();

        Iterator<Map.Entry<String, JsonNode>> iterator =
                propertiesNode.fields();

        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry =
                    iterator.next();

            Field field = parseField(
                    entry.getKey(),
                    entry.getValue(),
                    propertyNames,
                    false
            );

            fields.put(
                    entry.getKey(),
                    field
            );
        }

        return new UiSchema(
                Collections.unmodifiableMap(fields),
                Collections.unmodifiableSet(requiredFields)
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
     * 解析一个普通字段或对象列表字段。
     *
     * @param childField true 表示当前字段属于 OBJECT_LIST 的列表项
     */
    private Field parseField(
            String fieldName,
            JsonNode fieldSchema,
            Set<String> propertyNames,
            boolean childField) {

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
                .asText("")
                .trim();

        String component =
                resolveComponent(
                        fieldName,
                        type,
                        uiNode
                );

        if (component != null
                && !SUPPORTED_COMPONENTS.contains(component)) {
            throw badRequest(
                    "不支持的动态表单组件："
                            + component
            );
        }
        /*
         * OBJECT_LIST 子字段必须能映射到现有五类标量控件。
         * object、普通 array 等无法渲染的类型不能静默放行。
         */
        if (childField && component == null) {
            throw badRequest(
                    "OBJECT_LIST子字段只支持INPUT、NUMBER、DATE、RADIO、"
                            + "SELECT、REMOTE_SELECT、FILE_UPLOAD："
                            + fieldName
            );
        }
        if (childField && "OBJECT_LIST".equals(component)) {
            throw badRequest(
                    "不支持嵌套OBJECT_LIST："
                            + fieldName
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


        if ("OBJECT_LIST".equals(component)) {
            return parseObjectList(
                    fieldName,
                    fieldSchema,
                    uiNode,
                    type,
                    dependsOn
            );
        }

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

        return new Field(
                fieldName,
                resolveLabel(
                        fieldName,
                        fieldSchema,
                        uiNode
                ),
                type,
                component,
                dependsOn,
                optionSource,
                Map.of(),
                Set.of(),
                0,
                0
        );
    }

    /**
     * 解析一层对象列表。
     */
    private Field parseObjectList(
            String fieldName,
            JsonNode fieldSchema,
            JsonNode uiNode,
            String type,
            List<String> dependsOn) {

        if (!"array".equals(type)) {
            throw badRequest(
                    "OBJECT_LIST字段type必须是array："
                            + fieldName
            );
        }

        if (uiNode != null
                && uiNode.has("optionSource")) {
            throw badRequest(
                    "OBJECT_LIST不能配置optionSource："
                            + fieldName
            );
        }

        JsonNode itemsNode =
                fieldSchema.get("items");

        if (itemsNode == null
                || !itemsNode.isObject()) {
            throw badRequest(
                    "OBJECT_LIST必须配置items对象："
                            + fieldName
            );
        }

        if (!"object".equals(
                itemsNode.path("type")
                        .asText("")
                        .trim()
        )) {
            throw badRequest(
                    "OBJECT_LIST的items.type必须是object："
                            + fieldName
            );
        }

        JsonNode itemPropertiesNode =
                itemsNode.get("properties");

        if (itemPropertiesNode == null
                || !itemPropertiesNode.isObject()
                || itemPropertiesNode.isEmpty()) {
            throw badRequest(
                    "OBJECT_LIST的items.properties不能为空："
                            + fieldName
            );
        }

        Set<String> itemPropertyNames =
                readPropertyNames(
                        itemPropertiesNode
                );

        Set<String> itemRequiredFields =
                readRequiredFields(
                        itemsNode.get("required"),
                        itemPropertyNames,
                        "字段" + fieldName + ".items"
                );

        Map<String, Field> itemFields =
                new LinkedHashMap<>();

        Iterator<Map.Entry<String, JsonNode>> iterator =
                itemPropertiesNode.fields();

        while (iterator.hasNext()) {
            Map.Entry<String, JsonNode> entry =
                    iterator.next();

            Field child = parseField(
                    entry.getKey(),
                    entry.getValue(),
                    itemPropertyNames,
                    true
            );

            itemFields.put(
                    entry.getKey(),
                    child
            );
        }

        int minItems = readIntegerLimit(
                fieldSchema.get("minItems"),
                "字段" + fieldName + ".minItems",
                0,
                0
        );

        int maxItems = readIntegerLimit(
                fieldSchema.get("maxItems"),
                "字段" + fieldName + ".maxItems",
                OBJECT_LIST_DEFAULT_MAX_ITEMS,
                1
        );

        if (maxItems > OBJECT_LIST_HARD_LIMIT) {
            throw badRequest(
                    "OBJECT_LIST的maxItems不能超过50："
                            + fieldName
            );
        }

        if (minItems > maxItems) {
            throw badRequest(
                    "OBJECT_LIST的minItems不能大于maxItems："
                            + fieldName
            );
        }

        return new Field(
                fieldName,
                resolveLabel(
                        fieldName,
                        fieldSchema,
                        uiNode
                ),
                type,
                "OBJECT_LIST",
                dependsOn,
                null,
                Collections.unmodifiableMap(itemFields),
                Collections.unmodifiableSet(
                        itemRequiredFields
                ),
                minItems,
                maxItems
        );
    }

    /**
     * 解析字段所使用的表单控件。
     */
    private String resolveComponent(
            String fieldName,
            String type,
            JsonNode uiNode) {

        if (uiNode == null) {
            return defaultComponent(type);
        }

        String component = text(
                uiNode,
                "component"
        ).toUpperCase();

        if (!StringUtils.hasText(component)) {
            throw badRequest(
                    "字段x-ui.component不能为空："
                            + fieldName
            );
        }

        return component;
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
            Iterator<Map.Entry<String, JsonNode>> iterator =
                    mappingNode.fields();

            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> mapping =
                        iterator.next();

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
                    item.asText("")
                            .trim();

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
     * 读取 required 字段集合。
     */
    private Set<String> readRequiredFields(
            JsonNode requiredNode,
            Set<String> propertyNames,
            String schemaPath) {

        Set<String> result =
                new LinkedHashSet<>();

        if (requiredNode == null
                || requiredNode.isNull()) {
            return result;
        }

        if (!requiredNode.isArray()) {
            throw badRequest(
                    schemaPath
                            + ".required必须是字符串数组"
            );
        }

        for (JsonNode item : requiredNode) {
            String fieldName =
                    item.asText("")
                            .trim();

            if (!StringUtils.hasText(fieldName)
                    || !propertyNames.contains(fieldName)) {
                throw badRequest(
                        schemaPath
                                + ".required引用了未声明字段："
                                + fieldName
                );
            }

            result.add(fieldName);
        }

        return result;
    }

    /**
     * 读取 properties 中声明的字段名。
     */
    private Set<String> readPropertyNames(
            JsonNode propertiesNode) {

        Set<String> result =
                new LinkedHashSet<>();

        propertiesNode.fieldNames()
                .forEachRemaining(
                        result::add
                );

        return result;
    }

    /**
     * 读取 minItems、maxItems 等非负整数配置。
     */
    private int readIntegerLimit(
            JsonNode valueNode,
            String fieldPath,
            int defaultValue,
            int minimumValue) {

        if (valueNode == null
                || valueNode.isNull()) {
            return defaultValue;
        }

        if (!valueNode.isIntegralNumber()
                || !valueNode.canConvertToInt()) {
            throw badRequest(
                    fieldPath
                            + "必须是整数"
            );
        }

        int value = valueNode.intValue();

        if (value < minimumValue) {
            throw badRequest(
                    fieldPath
                            + "不能小于"
                            + minimumValue
            );
        }

        return value;
    }

    /**
     * 解析字段中文标签。
     */
    private String resolveLabel(
            String fieldName,
            JsonNode fieldSchema,
            JsonNode uiNode) {

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

        return StringUtils.hasText(label)
                ? label
                : fieldName;
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

        String role = roleNode
                .asText("")
                .trim();

        if (!"OPTION_SOURCE".equalsIgnoreCase(role)) {
            throw badRequest(
                    "不支持的能力角色："
                            + role
            );
        }
    }

    /**
     * 读取 JSON 根节点。
     */
    private JsonNode readRoot(String schemaJson) {
        if (!StringUtils.hasText(schemaJson)) {
            throw badRequest("inputSchemaJson不能为空");
        }

        try {
            JsonNode root =
                    objectMapper.readTree(schemaJson);

            if (root == null
                    || !root.isObject()) {
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

    /**
     * 读取必填字符串配置。
     */
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

    /**
     * 安全读取 JSON 字符串。
     */
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
     * 未配置 x-ui 时，根据基础类型提供默认控件。
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

        /**
         * 根据字段路径查询字段定义。
         *
         * 支持：
         * projectName
         * units
         * units[].unitId
         *
         * 不支持多层嵌套数组。
         */
        public Field findField(String fieldPath) {
            if (!StringUtils.hasText(fieldPath)) {
                return null;
            }

            int separatorIndex =
                    fieldPath.indexOf("[].");

            if (separatorIndex < 0) {
                return fields.get(fieldPath);
            }

            String rootName =
                    fieldPath.substring(
                            0,
                            separatorIndex
                    );

            String childName =
                    fieldPath.substring(
                            separatorIndex + 3
                    );

            /*
             * 第一版明确禁止多层列表路径。
             */
            if (!StringUtils.hasText(childName)
                    || childName.contains("[].")) {
                return null;
            }

            Field rootField =
                    fields.get(rootName);

            if (rootField == null
                    || !"OBJECT_LIST".equals(
                    rootField.component()
            )) {
                return null;
            }

            return rootField
                    .itemFields()
                    .get(childName);
        }
    }

    /**
     * 通用表单字段。
     *
     * 普通字段的 itemFields 和 itemRequiredFields 为空，
     * minItems、maxItems 为 0。
     */
    public record Field(
            String name,
            String label,
            String type,
            String component,
            List<String> dependsOn,
            OptionSource optionSource,
            Map<String, Field> itemFields,
            Set<String> itemRequiredFields,
            int minItems,
            int maxItems) {
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