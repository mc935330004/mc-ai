package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.common.enums.protocol.ValueType;

import java.util.List;

/**
 * Block中的单个安全展示值。
 *
 * rawValue只允许保存JSON标量，
 * 不能保存Map、集合或原始业务对象。
 */
public record DisplayValue(
        String key,
        String label,
        Object rawValue,
        String displayValue,
        ValueType valueType,
        String unit,
        Tone tone,
        List<FileValue> files) {

    public DisplayValue {
        key = requireText(key, "展示值key不能为空");
        label = normalize(label);
        displayValue = normalize(displayValue);
        unit = normalize(unit);
        valueType = valueType == null ? ValueType.TEXT : valueType;
        tone = tone == null ? Tone.DEFAULT : tone;
        files = files == null ? List.of() : List.copyOf(files);
        validateRawValue(rawValue);
    }

    /**
     * 真实值只能使用能够稳定序列化的标量。
     */
    private static void validateRawValue(Object rawValue) {
        if (rawValue == null || rawValue instanceof String || rawValue instanceof Number || rawValue instanceof Boolean) {
            return;
        }

        throw new IllegalArgumentException(
                "rawValue只允许字符串、数字、布尔值或null"
        );
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}