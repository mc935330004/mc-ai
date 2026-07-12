package org.example.ai.agent.capability.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 将 Swagger Schema 转换为 Agent 使用的标准 JSON Schema。
 */
@Component
@RequiredArgsConstructor
public class OpenApiSchemaConverter {

    private final ObjectMapper objectMapper;

    /**
     * 将 OpenAPI Schema 转换为 JSON 字符串。
     */
    public String toJson(Schema<?> schema) {
        if (schema == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(convert(schema));
        } catch (Exception e) {
            throw new IllegalArgumentException( "OpenAPI Schema转换失败：" + e.getMessage(), e);
        }
    }

    /**
     * 递归转换对象、数组和基础类型。
     */
    public ObjectNode convert(Schema<?> schema) {
        ObjectNode result = objectMapper.createObjectNode();

        if (schema == null) {
            result.put("type", "object");
            return result;
        }
        if (StringUtils.hasText(schema.get$ref())) {
            // 正常情况下 parser 已经解析引用；这里保留兜底信息。
            result.put("$ref", schema.get$ref());
            return result;
        }
        String type = resolveType(schema);
        result.put("type", type);
        putText(result, "description", schema.getDescription());
        putText(result, "format", schema.getFormat());

        if (schema.getDefault() != null) {
            result.set("default",objectMapper.valueToTree(schema.getDefault()));
        }

        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            result.set("enum",objectMapper.valueToTree(schema.getEnum()));
        }

        if ("array".equals(type)) {
            Schema<?> items = schema.getItems();
            result.set("items", items == null ? objectMapper.createObjectNode(): convert(items));
        }

        if ("object".equals(type)
                && schema.getProperties() != null) {
            ObjectNode properties = objectMapper.createObjectNode();
            for (Map.Entry<String, Schema> entry: schema.getProperties().entrySet()) {
                properties.set( entry.getKey(),convert(entry.getValue()));
            }
            result.set("properties", properties);
        }

        List<String> required = schema.getRequired();
        if (required != null && !required.isEmpty()) {
            ArrayNode requiredNode = objectMapper.createArrayNode();
            required.forEach(requiredNode::add);
            result.set("required", requiredNode);
        }

        return result;
    }

    /**
     * 某些 OpenAPI 文档没有明确填写 type，需要根据结构推断。
     */
    private String resolveType(Schema<?> schema) {
        if (StringUtils.hasText(schema.getType())) {
            return schema.getType();
        }
        if (schema.getItems() != null) {
            return "array";
        }
        if (schema.getProperties() != null) {
            return "object";
        }
        return "object";
    }

    private void putText(
            ObjectNode node,
            String name,
            String value
    ) {
        if (StringUtils.hasText(value)) {
            node.put(name, value);
        }
    }
}