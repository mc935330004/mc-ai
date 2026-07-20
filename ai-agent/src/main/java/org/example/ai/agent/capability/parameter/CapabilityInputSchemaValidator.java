package org.example.ai.agent.capability.parameter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务能力入参 JSON Schema 校验器。
 *
 * 第一版支持：
 *
 * 1. properties 参数白名单
 * 2. required 必填参数
 * 3. default 默认值
 * 4. string
 * 5. integer
 * 6. number
 * 7. boolean
 * 8. array
 * 9. object
 * 10. enum
 * 11. minimum / maximum
 * 12. minLength / maxLength
 *
 * 模型输出永远不能直接调用业务接口，
 * 必须经过本校验器生成 sanitizedInput。
 */
@Component
@RequiredArgsConstructor
public class CapabilityInputSchemaValidator {

    private static final Object INVALID_VALUE =new Object();

    private final ObjectMapper objectMapper;

    /**
     * 校验并清洗模型生成的接口参数。
     *
     * @param inputSchemaJson 能力配置中的 JSON Schema
     * @param rawInput        模型生成的原始参数
     */
    public CapabilityInputValidationResult validate(String inputSchemaJson, Map<String, Object> rawInput) {

        if (!StringUtils.hasText(inputSchemaJson)) {
            throw new BusinessException(400,"能力 inputSchemaJson 不能为空");
        }

        try {
            JsonNode rootSchema =objectMapper.readTree(inputSchemaJson);

            if (!rootSchema.isObject()) {
                throw new BusinessException( 400,"能力 inputSchemaJson 必须是 JSON 对象");
            }

            /*
             * OpenAPI 转换后的 Schema 通常明确包含 type=object。
             * 为兼容旧配置，存在 properties 时也按 object 处理。
             */
            String rootType = rootSchema.path("type").asText("");

            if (StringUtils.hasText(rootType)
                    && !"object".equals(rootType)) {
                throw new BusinessException(
                        400,
                        "能力 inputSchemaJson 根节点必须是 object" );
            }

            Map<String, Object> source =rawInput == null ? Map.of() : rawInput;

            List<String> missingParameters =new ArrayList<>();

            List<String> validationErrors = new ArrayList<>();

            List<String> removedParameters =new ArrayList<>();

            Map<String, Object> sanitizedInput =
                    sanitizeObject(
                            "$",
                            source,
                            rootSchema,
                            missingParameters,
                            validationErrors,
                            removedParameters
                    );

            return CapabilityInputValidationResult.builder()
                    .valid(missingParameters.isEmpty() && validationErrors.isEmpty())
                    .sanitizedInput(sanitizedInput)
                    .missingParameters(missingParameters)
                    .validationErrors(validationErrors)
                    .removedParameters(removedParameters)
                    .build();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    400,
                    "能力 inputSchemaJson 解析失败："
                            + exception.getMessage()
            );
        }
    }

    /**
     * 清洗一个 object 类型节点。
     */
    private Map<String, Object> sanitizeObject(
            String path,
            Map<String, Object> rawObject,
            JsonNode objectSchema,
            List<String> missingParameters,
            List<String> validationErrors,
            List<String> removedParameters) {

        Map<String, Object> result = new LinkedHashMap<>();

        JsonNode properties = objectSchema.path("properties");

        if (!properties.isObject()) {
            /*
             * 没有 properties 时不接受任何模型生成参数。
             */
            for (String key : rawObject.keySet()) {
                removedParameters.add(buildPath(path, key));
            }
            return result;
        }

        Set<String> requiredFields =readRequiredFields(objectSchema);

        /*
         * 删除 Schema 中不存在的参数。
         *
         * 这是参数白名单的核心，防止模型增加任意字段。
         */
        for (String rawName : rawObject.keySet()) {
            if (!properties.has(rawName)) {
                removedParameters.add(buildPath(path, rawName));
            }
        }

        properties.fields().forEachRemaining(entry -> {
            String fieldName = entry.getKey();
            JsonNode fieldSchema = entry.getValue();
            String fieldPath =buildPath(path, fieldName);

            boolean provided =rawObject.containsKey(fieldName)
                            && rawObject.get(fieldName) != null;

            Object rawValue = rawObject.get(fieldName);

            /*
             * 用户没有提供字段时，优先使用 Schema default。
             */
            if (!provided && fieldSchema.has("default")) {
                Object defaultValue =objectMapper.convertValue( fieldSchema.get("default"),Object.class);

                Object sanitizedDefault =
                        sanitizeValue(
                                fieldPath,
                                defaultValue,
                                fieldSchema,
                                missingParameters,
                                validationErrors,
                                removedParameters);

                if (sanitizedDefault != INVALID_VALUE) {
                    result.put(fieldName, sanitizedDefault);
                }

                return;
            }

            /*
             * 没有值并且是必填字段，记录缺失。
             */
            if (!provided) {
                if (requiredFields.contains(fieldName)) {
                    missingParameters.add(fieldPath);
                }
                return;
            }

            /*
             * 空字符串对于 required 字段也视为缺失。
             */
            if (rawValue instanceof String stringValue
                    && !StringUtils.hasText(stringValue)
                    && requiredFields.contains(fieldName)) {
                missingParameters.add(fieldPath);
                return;
            }

            Object sanitizedValue =
                    sanitizeValue(
                            fieldPath,
                            rawValue,
                            fieldSchema,
                            missingParameters,
                            validationErrors,
                            removedParameters
                    );

            if (sanitizedValue != INVALID_VALUE) {
                result.put(
                        fieldName,
                        sanitizedValue
                );
            }
        });

        return result;
    }

    /**
     * 根据字段 Schema 转换和校验一个值。
     */
    private Object sanitizeValue(
            String path,
            Object rawValue,
            JsonNode schema,
            List<String> missingParameters,
            List<String> validationErrors,
            List<String> removedParameters) {

        String type =
                schema.path("type").asText("");

        /*
         * 兼容未配置 type 但配置了 properties 的对象。
         */
        if (!StringUtils.hasText(type)
                && schema.path("properties").isObject()) {
            type = "object";
        }

        Object convertedValue;

        switch (type) {
            case "string" ->
                    convertedValue = convertString(
                            path,
                            rawValue,
                            schema,
                            validationErrors
                    );

            case "integer" ->
                    convertedValue = convertInteger(
                            path,
                            rawValue,
                            schema,
                            validationErrors
                    );

            case "number" ->
                    convertedValue = convertNumber(
                            path,
                            rawValue,
                            schema,
                            validationErrors
                    );

            case "boolean" ->
                    convertedValue = convertBoolean(
                            path,
                            rawValue,
                            validationErrors
                    );

            case "array" ->
                    convertedValue = convertArray(
                            path,
                            rawValue,
                            schema,
                            missingParameters,
                            validationErrors,
                            removedParameters
                    );

            case "object" ->
                    convertedValue = convertObject(
                            path,
                            rawValue,
                            schema,
                            missingParameters,
                            validationErrors,
                            removedParameters
                    );

            default -> convertedValue = rawValue;
        }

        if (convertedValue == INVALID_VALUE) {
            return INVALID_VALUE;
        }

        if (!validateEnum(
                path,
                convertedValue,
                schema,
                validationErrors)) {
            return INVALID_VALUE;
        }

        return convertedValue;
    }

    private Object convertString(
            String path,
            Object rawValue,
            JsonNode schema,
            List<String> errors) {

        if (rawValue instanceof Map<?, ?>
                || rawValue instanceof Collection<?>) {
            errors.add(path + " 必须是字符串");
            return INVALID_VALUE;
        }

        String value =
                String.valueOf(rawValue).trim();

        if (schema.has("minLength")
                && value.length()
                < schema.get("minLength").asInt()) {
            errors.add(
                    path + " 长度不能小于 "
                            + schema.get("minLength").asInt()
            );
            return INVALID_VALUE;
        }

        if (schema.has("maxLength")
                && value.length()
                > schema.get("maxLength").asInt()) {
            errors.add(
                    path + " 长度不能大于 "
                            + schema.get("maxLength").asInt()
            );
            return INVALID_VALUE;
        }

        return value;
    }

    private Object convertInteger(
            String path,
            Object rawValue,
            JsonNode schema,
            List<String> errors) {

        try {
            BigDecimal number =
                    new BigDecimal(
                            String.valueOf(rawValue).trim()
                    );

            /*
             * integer 不允许存在小数部分。
             */
            if (number.stripTrailingZeros().scale() > 0) {
                errors.add(path + " 必须是整数");
                return INVALID_VALUE;
            }

            validateNumberRange(
                    path,
                    number,
                    schema,
                    errors
            );

            if (hasErrorForPath(errors, path)) {
                return INVALID_VALUE;
            }

            return number.longValueExact();
        } catch (Exception exception) {
            errors.add(path + " 必须是整数");
            return INVALID_VALUE;
        }
    }

    private Object convertNumber(
            String path,
            Object rawValue,
            JsonNode schema,
            List<String> errors) {

        try {
            BigDecimal number =
                    new BigDecimal(
                            String.valueOf(rawValue).trim()
                    );

            validateNumberRange(
                    path,
                    number,
                    schema,
                    errors
            );

            if (hasErrorForPath(errors, path)) {
                return INVALID_VALUE;
            }

            return number;
        } catch (Exception exception) {
            errors.add(path + " 必须是数字");
            return INVALID_VALUE;
        }
    }

    private Object convertBoolean(
            String path,
            Object rawValue,
            List<String> errors) {

        if (rawValue instanceof Boolean) {
            return rawValue;
        }

        String text =
                String.valueOf(rawValue).trim();

        if ("true".equalsIgnoreCase(text)) {
            return true;
        }

        if ("false".equalsIgnoreCase(text)) {
            return false;
        }

        errors.add(path + " 必须是 true 或 false");
        return INVALID_VALUE;
    }

    private Object convertArray(
            String path,
            Object rawValue,
            JsonNode schema,
            List<String> missingParameters,
            List<String> errors,
            List<String> removedParameters) {

        if (!(rawValue instanceof Collection<?> collection)) {
            errors.add(path + " 必须是数组");
            return INVALID_VALUE;
        }
        /*
         * 数组长度确定性校验。
         */
        if (schema.has("minItems") && collection.size() < schema.get("minItems").asInt()) {
            errors.add(path + " 至少需要 "+ schema.get("minItems").asInt() + " 项");
            return INVALID_VALUE;
        }

        if (schema.has("maxItems")&& collection.size() > schema.get("maxItems").asInt()) {
            errors.add(path + " 最多允许 "+ schema.get("maxItems").asInt()+ " 项" );
            return INVALID_VALUE;
        }
        /*
         * uniqueItems=true时不允许重复项目。
         */
        if (schema.path("uniqueItems")
                .asBoolean(false)) {

            Set<JsonNode> uniqueValues =
                    new LinkedHashSet<>();

            for (Object item : collection) {
                JsonNode itemNode =
                        objectMapper.valueToTree(item);

                if (!uniqueValues.add(itemNode)) {
                    errors.add(
                            path + " 不允许包含重复项"
                    );

                    return INVALID_VALUE;
                }
            }
        }
        JsonNode itemSchema =schema.path("items");
        List<Object> result = new ArrayList<>();
        int index = 0;

        for (Object item : collection) {
            Object sanitizedItem =
                    itemSchema.isMissingNode()
                            ? item
                            : sanitizeValue(
                                    path + "[" + index + "]",
                                    item,
                                    itemSchema,
                                    missingParameters,
                                    errors,
                                    removedParameters
                            );

            if (sanitizedItem != INVALID_VALUE) {
                result.add(sanitizedItem);
            }

            index++;
        }

        return result;
    }

    private Object convertObject(
            String path,
            Object rawValue,
            JsonNode schema,
            List<String> missingParameters,
            List<String> errors,
            List<String> removedParameters) {

        if (!(rawValue instanceof Map<?, ?> sourceMap)) {
            errors.add(path + " 必须是对象");
            return INVALID_VALUE;
        }

        Map<String, Object> source =
                new LinkedHashMap<>();

        sourceMap.forEach((key, value) -> {
            if (key != null) {
                source.put(
                        String.valueOf(key),
                        value
                );
            }
        });

        return sanitizeObject(
                path,
                source,
                schema,
                missingParameters,
                errors,
                removedParameters
        );
    }

    /**
     * 校验 enum。
     */
    private boolean validateEnum(
            String path,
            Object value,
            JsonNode schema,
            List<String> errors) {

        JsonNode enumNode =
                schema.path("enum");

        if (!enumNode.isArray()
                || enumNode.isEmpty()) {
            return true;
        }

        JsonNode valueNode =
                objectMapper.valueToTree(value);

        for (JsonNode allowed : enumNode) {
            if (allowed.equals(valueNode)
                    || allowed.asText().equals(
                    String.valueOf(value))) {
                return true;
            }
        }

        errors.add(
                path + " 不在允许的枚举值中："
                        + enumNode
        );

        return false;
    }

    private void validateNumberRange(
            String path,
            BigDecimal value,
            JsonNode schema,
            List<String> errors) {

        if (schema.has("minimum")) {
            BigDecimal minimum =
                    schema.get("minimum").decimalValue();

            if (value.compareTo(minimum) < 0) {
                errors.add(
                        path + " 不能小于 " + minimum
                );
            }
        }

        if (schema.has("maximum")) {
            BigDecimal maximum =
                    schema.get("maximum").decimalValue();

            if (value.compareTo(maximum) > 0) {
                errors.add(
                        path + " 不能大于 " + maximum
                );
            }
        }
    }

    private Set<String> readRequiredFields(
            JsonNode objectSchema) {

        Set<String> result =
                new LinkedHashSet<>();

        JsonNode required =
                objectSchema.path("required");

        if (!required.isArray()) {
            return result;
        }

        required.forEach(item -> {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        });

        return result;
    }

    private boolean hasErrorForPath(
            List<String> errors,
            String path) {

        return errors.stream()
                .anyMatch(error ->
                        error.startsWith(path + " ")
                );
    }

    private String buildPath(
            String parent,
            String fieldName) {

        if ("$".equals(parent)) {
            return "$." + fieldName;
        }

        return parent + "." + fieldName;
    }
}