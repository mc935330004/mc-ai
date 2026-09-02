package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 按字段策略把标准事实拆分为统计、展示、导出和模型四个相互独立的安全通道。
 *
 * 未知事实默认拒绝，且本类不会记录原始事实。
 */
@Component
public final class BusinessFactSanitizer {

    public static final String SUMMARY_ONLY_VALUE = "仅用于汇总";

    private final ReportDatasetValidator validator;
    private final ObjectMapper objectMapper;

    public BusinessFactSanitizer(
            ReportDatasetValidator validator,
            ObjectMapper objectMapper) {
        this.validator = Objects.requireNonNull(validator, "validator不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    /**
     * 仅遍历已声明策略，避免任何未登记的原始字段进入结果。
     */
    public SanitizedFacts sanitize(
            Map<String, Object> standardFacts,
            List<FieldPolicy> fieldPolicies) {
        Map<String, Object> factSnapshot = standardFacts == null
                ? Map.of()
                : new LinkedHashMap<>(standardFacts);
        List<FieldPolicy> policySnapshot = fieldPolicies == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(fieldPolicies));
        validator.validateFieldPolicies(policySnapshot);

        Map<String, Object> calculationFacts = new LinkedHashMap<>();
        Map<String, Object> displayFacts = new LinkedHashMap<>();
        Map<String, Object> exportFacts = new LinkedHashMap<>();
        Map<String, Object> modelFacts = new LinkedHashMap<>();
        for (FieldPolicy policy : policySnapshot) {
            Object rawValue = factSnapshot.containsKey(policy.factCode())
                    ? factSnapshot.get(policy.factCode())
                    : null;
            boolean missing = rawValue == null;
            Object calculationValue = missing
                    ? MissingValue.INSTANCE
                    : freeze(rawValue);
            Object visibleValue = missing
                    ? MissingValue.INSTANCE
                    : mask(calculationValue, policy.maskStrategy());

            if (policy.calculable()) {
                calculationFacts.put(policy.factCode(), calculationValue);
            }
            if (policy.displayable()) {
                displayFacts.put(policy.factCode(), visibleValue);
            }
            if (policy.exportable()) {
                exportFacts.put(policy.factCode(), visibleValue);
            }
            if (policy.modelVisible()) {
                modelFacts.put(policy.factCode(), visibleValue);
            }
        }
        return new SanitizedFacts(
                calculationFacts,
                displayFacts,
                exportFacts,
                modelFacts
        );
    }

    /**
     * 递归冻结标准 JSON 风格容器，保留数值等标量的原始 Java 类型。
     */
    private Object freeze(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> frozen = new LinkedHashMap<>();
            map.forEach((key, item) -> frozen.put(key, freeze(item)));
            return Collections.unmodifiableMap(frozen);
        }
        if (value instanceof Set<?> set) {
            Set<Object> frozen = new LinkedHashSet<>();
            set.forEach(item -> frozen.add(freeze(item)));
            return Collections.unmodifiableSet(frozen);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> frozen = new ArrayList<>(collection.size());
            collection.forEach(item -> frozen.add(freeze(item)));
            return Collections.unmodifiableList(frozen);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> frozen = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                frozen.add(freeze(Array.get(value, index)));
            }
            return Collections.unmodifiableList(frozen);
        }
        return value;
    }

    private Object mask(Object value, String strategy) {
        return switch (strategy) {
            case "NONE" -> value;
            case "PARTIAL" -> partialMask(String.valueOf(value));
            case "HASH" -> sha256(value);
            case "SUMMARY_ONLY" -> SUMMARY_ONLY_VALUE;
            default -> throw new IllegalArgumentException(
                    "不支持的maskStrategy：" + strategy
            );
        };
    }

    private String partialMask(String value) {
        int[] codePoints = value.codePoints().toArray();
        if (codePoints.length == 0) {
            return value;
        }
        if (codePoints.length <= 4) {
            return new String(codePoints, 0, 1) + "*".repeat(codePoints.length - 1);
        }
        return "*".repeat(codePoints.length - 4)
                + new String(codePoints, codePoints.length - 4, 4);
    }

    private String sha256(Object value) {
        byte[] source;
        if (value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>) {
            source = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        } else {
            try {
                JsonNode canonical = canonicalize(objectMapper.valueToTree(value));
                source = objectMapper.writeValueAsBytes(canonical);
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                throw new IllegalArgumentException("HASH字段无法规范化", exception);
            }
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(source)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Set<String> sortedNames = new TreeSet<>();
            Iterator<String> names = node.fieldNames();
            names.forEachRemaining(sortedNames::add);
            for (String name : sortedNames) {
                result.set(name, canonicalize(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return node.deepCopy();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 明确区分“缺失”与合法数值零。
     */
    public enum MissingValue {
        INSTANCE
    }

    /**
     * 四个事实通道均在构造时完成结构防御复制并保持不可变。
     */
    public record SanitizedFacts(
            Map<String, Object> calculationFacts,
            Map<String, Object> displayFacts,
            Map<String, Object> exportFacts,
            Map<String, Object> modelFacts) {

        public SanitizedFacts {
            calculationFacts = immutableMap(calculationFacts);
            displayFacts = immutableMap(displayFacts);
            exportFacts = immutableMap(exportFacts);
            modelFacts = immutableMap(modelFacts);
        }
    }
}
