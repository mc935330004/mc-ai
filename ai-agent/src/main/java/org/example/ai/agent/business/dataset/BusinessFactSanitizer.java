package org.example.ai.agent.business.dataset;

import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按字段策略把标准事实拆分为统计、展示、导出和模型四个相互独立的安全通道。
 *
 * 未知事实默认拒绝，且本类不会记录原始事实。
 */
@Component
public final class BusinessFactSanitizer {

    public static final String SUMMARY_ONLY_VALUE = "仅用于汇总";

    private final ReportDatasetValidator validator;

    public BusinessFactSanitizer(ReportDatasetValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator不能为空");
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
                    : validator.freezeSafeValue(rawValue);
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
        if (codePoints.length == 1) {
            return "*";
        }
        if (codePoints.length <= 4) {
            return new String(codePoints, 0, 1) + "*".repeat(codePoints.length - 1);
        }
        return "*".repeat(codePoints.length - 4)
                + new String(codePoints, codePoints.length - 4, 4);
    }

    private String sha256(Object value) {
        return ContentHashUtils.sha256(validator.canonicalSafeValue(value));
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
