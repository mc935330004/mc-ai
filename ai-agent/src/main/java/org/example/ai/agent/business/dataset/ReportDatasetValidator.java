package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 报告数据集运行时边界校验器，集中处理输入映射、字段策略及安全值冻结。
 */
@Component
public final class ReportDatasetValidator {

    private static final int MAX_SAFE_VALUE_DEPTH = 64;
    private static final Set<Class<?>> IMMUTABLE_NUMBER_TYPES = Set.of(
            Byte.class,
            Short.class,
            Integer.class,
            Long.class,
            Float.class,
            Double.class,
            BigInteger.class,
            BigDecimal.class
    );
    private static final Set<String> RESERVED_INPUT_NAMES = Set.of(
            "workflowcode",
            "capabilitycode",
            "authorization",
            "securecontext",
            "usercontext"
    );
    private static final Set<String> MASK_STRATEGIES = Set.of(
            "NONE",
            "PARTIAL",
            "HASH",
            "SUMMARY_ONLY"
    );

    /**
     * 校验一次运行时映射，确保参数只能来自规范输入、持久化映射和目标 Schema 的交集。
     */
    public void validateCanonicalInputMapping(
            Map<String, Object> canonicalValues,
            Map<String, String> explicitMapping,
            JsonNode inputSchema) {
        JsonNode properties = requireObjectSchema(inputSchema);
        Set<String> mappedTargets = new HashSet<>();
        for (Map.Entry<String, String> entry : explicitMapping.entrySet()) {
            String canonicalName = requireExactText(entry.getKey(), "规范参数名不能为空");
            String targetName = requireExactText(entry.getValue(), "目标工作流参数名不能为空");
            rejectReserved(canonicalName, "规范参数");
            rejectReserved(targetName, "目标参数");
            if (!properties.has(targetName)) {
                throw new IllegalArgumentException(
                        "目标参数不在inputSchema.properties中：" + targetName
                );
            }
            if (!mappedTargets.add(targetName)) {
                throw new IllegalArgumentException("目标参数重复映射：" + targetName);
            }
        }

        for (String canonicalName : canonicalValues.keySet()) {
            String validatedName = requireExactText(canonicalName, "规范参数名不能为空");
            rejectReserved(validatedName, "规范参数");
            if (!explicitMapping.containsKey(validatedName)) {
                throw new IllegalArgumentException(
                        "规范参数缺少显式映射：" + validatedName
                );
            }
        }

        JsonNode required = inputSchema.get("required");
        if (required == null) {
            return;
        }
        if (!required.isArray()) {
            throw new IllegalArgumentException("inputSchema.required必须是数组");
        }
        Set<String> requiredNames = new HashSet<>();
        for (JsonNode item : required) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("inputSchema.required只能包含字符串");
            }
            String targetName = requireExactText(item.textValue(), "required参数名不能为空");
            if (!properties.has(targetName)) {
                throw new IllegalArgumentException(
                        "required参数不在inputSchema.properties中：" + targetName
                );
            }
            if (!requiredNames.add(targetName)) {
                throw new IllegalArgumentException("required参数重复：" + targetName);
            }
            String canonicalName = canonicalNameForTarget(explicitMapping, targetName);
            if (canonicalName == null || canonicalValues.get(canonicalName) == null) {
                throw new IllegalArgumentException(
                        "required目标参数映射后缺少非null值：" + targetName
                );
            }
        }
    }

    /**
     * 校验运行时字段策略协议；空策略集合表示默认拒绝全部未知事实。
     */
    public void validateFieldPolicies(List<FieldPolicy> policies) {
        Set<String> factCodes = new HashSet<>();
        for (FieldPolicy policy : policies) {
            if (policy == null) {
                throw new IllegalArgumentException("FieldPolicy不能包含空项");
            }
            String factCode = requireExactText(policy.factCode(), "factCode不能为空");
            requireExactText(policy.factType(), "factType不能为空");
            requireExactText(policy.grain(), "grain不能为空");
            String maskStrategy = requireExactText(
                    policy.maskStrategy(),
                    "maskStrategy不能为空"
            );
            if (!factCodes.add(factCode)) {
                throw new IllegalArgumentException("factCode重复：" + factCode);
            }
            if (!MASK_STRATEGIES.contains(maskStrategy)) {
                throw new IllegalArgumentException(
                        "不支持的maskStrategy：" + maskStrategy
                );
            }
        }
    }

    /**
     * 递归复制并冻结可安全跨执行边界传递的值。
     */
    Object freezeSafeValue(Object value) {
        return freezeSafeValue(value, new IdentityHashMap<>(), 0);
    }

    /**
     * 为已经冻结的安全值生成带类型标签的确定性表达。
     */
    String canonicalSafeValue(Object safeValue) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(safeValue, canonical);
        return canonical.toString();
    }

    private Object freezeSafeValue(
            Object value,
            IdentityHashMap<Object, Boolean> recursionPath,
            int depth) {
        if (depth > MAX_SAFE_VALUE_DEPTH) {
            throw new BusinessException(
                    400,
                    "安全值嵌套深度超过" + MAX_SAFE_VALUE_DEPTH
            );
        }
        if (value == null || isImmutableScalar(value)) {
            return value;
        }
        if (!isContainer(value)) {
            throw new BusinessException(
                    400,
                    "不支持的安全值类型：" + value.getClass().getName()
            );
        }
        if (recursionPath.put(value, Boolean.TRUE) != null) {
            throw new BusinessException(400, "安全值存在循环引用");
        }
        try {
            if (value instanceof Map<?, ?> map) {
                return freezeMap(map, recursionPath, depth);
            }
            if (value instanceof Set<?> set) {
                return freezeSet(set, recursionPath, depth);
            }
            if (value instanceof Collection<?> collection) {
                return freezeCollection(collection, recursionPath, depth);
            }
            return freezeArray(value, recursionPath, depth);
        } finally {
            recursionPath.remove(value);
        }
    }

    private Map<String, Object> freezeMap(
            Map<?, ?> source,
            IdentityHashMap<Object, Boolean> recursionPath,
            int depth) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new BusinessException(400, "安全值Map key只允许String");
            }
            frozen.put(
                    key,
                    freezeSafeValue(entry.getValue(), recursionPath, depth + 1)
            );
        }
        return Collections.unmodifiableMap(frozen);
    }

    private Set<Object> freezeSet(
            Set<?> source,
            IdentityHashMap<Object, Boolean> recursionPath,
            int depth) {
        List<FrozenSetElement> sorted = new ArrayList<>(source.size());
        for (Object item : source) {
            Object frozen = freezeSafeValue(item, recursionPath, depth + 1);
            sorted.add(new FrozenSetElement(frozen, canonicalSafeValue(frozen)));
        }
        sorted.sort((left, right) -> left.canonical().compareTo(right.canonical()));
        Set<Object> result = new LinkedHashSet<>();
        sorted.forEach(item -> result.add(item.value()));
        return Collections.unmodifiableSet(result);
    }

    private List<Object> freezeCollection(
            Collection<?> source,
            IdentityHashMap<Object, Boolean> recursionPath,
            int depth) {
        List<Object> frozen = new ArrayList<>(source.size());
        for (Object item : source) {
            frozen.add(freezeSafeValue(item, recursionPath, depth + 1));
        }
        return Collections.unmodifiableList(frozen);
    }

    private List<Object> freezeArray(
            Object source,
            IdentityHashMap<Object, Boolean> recursionPath,
            int depth) {
        int length = Array.getLength(source);
        List<Object> frozen = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            frozen.add(freezeSafeValue(Array.get(source, index), recursionPath, depth + 1));
        }
        return Collections.unmodifiableList(frozen);
    }

    private boolean isContainer(Object value) {
        return value instanceof Map<?, ?>
                || value instanceof Collection<?>
                || value.getClass().isArray();
    }

    private boolean isImmutableScalar(Object value) {
        return value instanceof String
                || value instanceof Boolean
                || value instanceof Character
                || IMMUTABLE_NUMBER_TYPES.contains(value.getClass())
                || value instanceof UUID
                || value instanceof Enum<?>
                || isJavaTimeValue(value);
    }

    private boolean isJavaTimeValue(Object value) {
        return "java.time".equals(value.getClass().getPackageName())
                && (value instanceof TemporalAccessor
                || value instanceof TemporalAmount
                || value instanceof ZoneId);
    }

    private void appendCanonical(Object value, StringBuilder target) {
        if (value == null) {
            target.append("N;");
            return;
        }
        if (isImmutableScalar(value)) {
            appendToken(target, "V", value.getClass().getName());
            String scalarText = value instanceof Enum<?> enumValue
                    ? enumValue.name()
                    : String.valueOf(value);
            appendToken(target, "X", scalarText);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            target.append("M{");
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new BusinessException(400, "安全值Map key只允许String");
                }
                sorted.put(key, entry.getValue());
            }
            sorted.forEach((key, item) -> {
                appendToken(target, "K", key);
                appendCanonical(item, target);
            });
            target.append("};");
            return;
        }
        if (value instanceof Set<?> set) {
            List<String> elements = new ArrayList<>(set.size());
            for (Object item : set) {
                elements.add(canonicalSafeValue(item));
            }
            Collections.sort(elements);
            target.append("S[");
            elements.forEach(item -> appendToken(target, "E", item));
            target.append("];");
            return;
        }
        if (value instanceof List<?> list) {
            target.append("L[");
            list.forEach(item -> appendCanonical(item, target));
            target.append("];");
            return;
        }
        throw new BusinessException(
                400,
                "不支持的安全值类型：" + value.getClass().getName()
        );
    }

    private void appendToken(StringBuilder target, String tag, String value) {
        target.append(tag)
                .append(value.length())
                .append(':')
                .append(value)
                .append(';');
    }

    private record FrozenSetElement(Object value, String canonical) {
    }

    private JsonNode requireObjectSchema(JsonNode inputSchema) {
        if (inputSchema == null || !inputSchema.isObject()) {
            throw new IllegalArgumentException("inputSchema必须是JSON对象");
        }
        JsonNode type = inputSchema.get("type");
        if (type != null && (!type.isTextual() || !"object".equals(type.textValue()))) {
            throw new IllegalArgumentException("inputSchema.type必须为object");
        }
        JsonNode properties = inputSchema.get("properties");
        if (properties == null || !properties.isObject()) {
            throw new IllegalArgumentException("inputSchema.properties必须是JSON对象");
        }
        return properties;
    }

    private String canonicalNameForTarget(
            Map<String, String> explicitMapping,
            String targetName) {
        for (Map.Entry<String, String> entry : explicitMapping.entrySet()) {
            if (targetName.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void rejectReserved(String name, String kind) {
        if (RESERVED_INPUT_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(kind + "使用了保留字段：" + name);
        }
    }

    private String requireExactText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(message.replace("不能为空", "不能包含首尾空白"));
        }
        return value;
    }
}
