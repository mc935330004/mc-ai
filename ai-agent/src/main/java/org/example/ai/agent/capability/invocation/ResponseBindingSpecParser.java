package org.example.ai.agent.capability.invocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.example.ai.agent.capability.invocation.model.ResponseBindingSpec;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 响应绑定配置解析器。
 *
 * 只负责解析和校验配置，不负责解释真实响应。
 */
@Component
public class ResponseBindingSpecParser {

    public static final String SUPPORTED_VERSION = "1.0";

    private static final int MAX_SUCCESS_VALUE_COUNT = 20;

    /**
     * 第一版只支持受限JSON路径：
     *
     * $
     * $.data
     * $.data.records
     * $.data.records.0
     *
     * 不支持：
     * $..data
     * $.records[*]
     * $.records[?()]
     */
    private static final Pattern SIMPLE_JSON_PATH =
            Pattern.compile(
                    "^\\$(?:\\.[\\p{L}\\p{N}_-]+)*$"
            );

    private final ObjectReader responseBindingReader;

    public ResponseBindingSpecParser(ObjectMapper objectMapper) {
        this.responseBindingReader = objectMapper
                .readerFor(ResponseBindingSpec.class)
                .with(
                        DeserializationFeature
                                .FAIL_ON_UNKNOWN_PROPERTIES
                );
    }

    public ResponseBindingSpec parse(String bindingJson) {
        if (!StringUtils.hasText(bindingJson)) {
            throw invalid("responseBindingJson不能为空");
        }

        ResponseBindingSpec spec;

        try {
            spec = responseBindingReader.readValue(bindingJson);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "响应绑定配置JSON解析失败：" +
                            safeJsonMessage(exception)
            );
        }

        if (spec == null) {
            throw invalid("响应绑定配置不能为空");
        }

        validateVersion(spec);
        normalizeAndValidatePaths(spec);
        validateBusinessCodeCondition(spec);
        validateSuccessFlagCondition(spec);

        return spec;
    }

    private void validateVersion(ResponseBindingSpec spec) {
        if (!SUPPORTED_VERSION.equals(spec.getVersion())) {
            throw invalid( "不支持的响应绑定版本：" +
                            spec.getVersion() +
                            "，当前仅支持：" +
                            SUPPORTED_VERSION
            );
        }
    }

    private void normalizeAndValidatePaths(ResponseBindingSpec spec) {

        spec.setBusinessCodePath(normalizeOptionalPath(
                        "businessCodePath",
                        spec.getBusinessCodePath()
                )
        );

        spec.setSuccessFlagPath(
                normalizeOptionalPath(
                        "successFlagPath",
                        spec.getSuccessFlagPath()
                )
        );

        spec.setMessagePath(
                normalizeOptionalPath(
                        "messagePath",
                        spec.getMessagePath()
                )
        );

        if (!StringUtils.hasText(spec.getDataPath())) {
            throw invalid("dataPath不能为空");
        }

        spec.setDataPath(
                normalizeRequiredPath(
                        "dataPath",
                        spec.getDataPath()
                )
        );
    }

    private void validateBusinessCodeCondition(
            ResponseBindingSpec spec) {

        List<JsonNode> successValues =
                spec.getSuccessValues();

        if (successValues == null) {
            successValues = List.of();
        }

        boolean configuredCodePath =
                StringUtils.hasText(
                        spec.getBusinessCodePath()
                );

        if (configuredCodePath
                && successValues.isEmpty()) {

            throw invalid(
                    "配置businessCodePath时，" +
                            "successValues不能为空"
            );
        }

        if (!configuredCodePath
                && !successValues.isEmpty()) {

            throw invalid(
                    "配置successValues时，" +
                            "businessCodePath不能为空"
            );
        }

        if (successValues.size()
                > MAX_SUCCESS_VALUE_COUNT) {

            throw invalid(
                    "successValues最多配置" +
                            MAX_SUCCESS_VALUE_COUNT +
                            "个值"
            );
        }

        for (int index = 0;
             index < successValues.size();
             index++) {

            JsonNode value = successValues.get(index);

            if (value == null
                    || value.isNull()
                    || !value.isValueNode()) {

                throw invalid(
                        "successValues[" +
                                index +
                                "]必须是字符串、数字或布尔值"
                );
            }
        }

        spec.setSuccessValues(
                List.copyOf(successValues)
        );
    }

    private void validateSuccessFlagCondition(
            ResponseBindingSpec spec) {

        boolean configuredFlagPath =
                StringUtils.hasText(
                        spec.getSuccessFlagPath()
                );

        JsonNode flagValue =
                spec.getSuccessFlagValue();

        if (!configuredFlagPath) {
            if (flagValue != null
                    && !flagValue.isNull()) {

                throw invalid(
                        "配置successFlagValue时，" +
                                "successFlagPath不能为空"
                );
            }

            spec.setSuccessFlagValue(null);
            return;
        }

        /*
         * successFlagPath配置后，
         * successFlagValue默认使用true。
         */
        if (flagValue == null || flagValue.isNull()) {
            spec.setSuccessFlagValue(
                    BooleanNode.TRUE
            );
            return;
        }

        if (!flagValue.isValueNode()) {
            throw invalid(
                    "successFlagValue必须是" +
                            "字符串、数字或布尔值"
            );
        }
    }

    private String normalizeOptionalPath(
            String fieldName,
            String path) {

        if (path == null) {
            return null;
        }

        if (!StringUtils.hasText(path)) {
            throw invalid(fieldName + "不能为空字符串");
        }

        return normalizeRequiredPath(
                fieldName,
                path
        );
    }

    private String normalizeRequiredPath(
            String fieldName,
            String path) {

        String normalized = path.trim();

        if (normalized.length() > 300) {
            throw invalid(
                    fieldName + "长度不能超过300"
            );
        }

        if (!SIMPLE_JSON_PATH
                .matcher(normalized)
                .matches()) {

            throw invalid(
                    fieldName +
                            "不是合法的受限JSON路径：" +
                            normalized
            );
        }

        return normalized;
    }

    private String safeJsonMessage(
            JsonProcessingException exception) {

        String message =
                exception.getOriginalMessage();

        if (!StringUtils.hasText(message)) {
            return "JSON格式不正确";
        }

        return message.length() <= 300
                ? message
                : message.substring(0, 300);
    }

    private BusinessException invalid(
            String message) {

        return new BusinessException(
                400,
                "响应绑定配置无效：" + message
        );
    }
}