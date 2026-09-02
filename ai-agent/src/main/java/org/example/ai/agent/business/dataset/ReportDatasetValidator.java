package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 报告数据集运行时协议校验器，只校验显式输入映射、目标 Schema 和字段策略形状。
 */
@Component
public final class ReportDatasetValidator {

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
