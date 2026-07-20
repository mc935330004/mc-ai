package org.example.ai.agent.capability.invocation.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.ResponseBindingSpecParser;
import org.example.ai.agent.capability.invocation.model.ResponseBindingSpec;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 能力响应解释器。
 *
 * HTTP调用器只判断HTTP是否成功；
 * 本类继续判断业务层是否成功。
 */
@Component
@RequiredArgsConstructor
public class CapabilityResponseInterpreter {

    private static final int MAX_MESSAGE_LENGTH = 300;
    private static final int MAX_BUSINESS_CODE_LENGTH = 100;

    private final ObjectMapper objectMapper;
    private final ResponseBindingSpecParser responseBindingSpecParser;
    private final SimpleJsonPathReader jsonPathReader;

    /**
     * 解释业务接口响应。
     *
     * @param capability                  能力定义
     * @param raw                         原始HTTP响应
     * @param allowMissingBindingForDraft 是否允许草稿没有响应绑定
     */
    public ResponseInterpretationResult interpret(CapabilityDefinition capability,Object raw,boolean allowMissingBindingForDraft) {

        ResponseBindingSpec spec =
                loadResponseBinding(
                        capability,
                        allowMissingBindingForDraft
                );

        JsonNode root = raw == null
                ? NullNode.getInstance()
                : objectMapper.valueToTree(raw);

        String businessMessage =
                readBusinessMessage(
                        root,
                        spec.getMessagePath()
                );

        String businessCode = null;
        boolean codeAccepted = true;
        boolean flagAccepted = true;
        /*
         * 读取并验证业务状态码。
         */
        if (StringUtils.hasText(
                spec.getBusinessCodePath())) {

            SimpleJsonPathReader.ReadResult codeResult =
                    jsonPathReader.read(
                            root,
                            spec.getBusinessCodePath()
                    );

            if (!isReadableScalar(codeResult)) {
                return ResponseInterpretationResult.failure(
                        "BUSINESS_RESPONSE_STRUCTURE_INVALID",
                        "业务系统响应缺少有效业务状态码：" +
                                spec.getBusinessCodePath(),
                        null,
                        businessMessage
                );
            }

            businessCode = safeScalarText(
                    codeResult.value(),
                    MAX_BUSINESS_CODE_LENGTH
            );

            codeAccepted =
                    spec.getSuccessValues()
                            .stream()
                            .anyMatch(expected ->
                                    scalarEquals(
                                            codeResult.value(),
                                            expected
                                    )
                            );
        }

        /*
         * 读取并验证成功标记。
         */
        if (StringUtils.hasText(spec.getSuccessFlagPath())) {

            SimpleJsonPathReader.ReadResult flagResult =jsonPathReader.read(root,spec.getSuccessFlagPath());

            if (!isReadableScalar(flagResult)) {
                return ResponseInterpretationResult.failure(
                        "BUSINESS_RESPONSE_STRUCTURE_INVALID",
                        "业务系统响应缺少有效成功标记：" +
                                spec.getSuccessFlagPath(),
                        businessCode,
                        businessMessage
                );
            }

            flagAccepted = scalarEquals(
                    flagResult.value(),
                    spec.getSuccessFlagValue()
            );
        }

        /*
         * 同时配置code和flag时使用AND关系。
         */
        if (!codeAccepted || !flagAccepted) {
            String errorMessage =StringUtils.hasText(businessMessage)
                            ? businessMessage
                            : "业务系统返回失败";

            return ResponseInterpretationResult.failure(
                    "BUSINESS_RESPONSE_REJECTED",
                    errorMessage,
                    businessCode,
                    businessMessage
            );
        }

        /*
         * 业务成功后再提取data，
         * 防止错误响应被误当成正常业务数据交给大模型。
         */
        SimpleJsonPathReader.ReadResult dataResult =
                jsonPathReader.read(
                        root,
                        spec.getDataPath()
                );

        boolean missingData =
                !dataResult.found()
                        || dataResult.value() == null
                        || dataResult.value().isNull();

        if (spec.isDataRequired() && missingData) {
            return ResponseInterpretationResult.failure(
                    "BUSINESS_RESPONSE_DATA_MISSING",
                    "业务系统响应缺少数据节点：" +
                            spec.getDataPath(),
                    businessCode,
                    businessMessage
            );
        }

        Object data = missingData
                ? null
                : objectMapper.convertValue(
                        dataResult.value(),
                        Object.class
                );

        return ResponseInterpretationResult.success(
                businessCode,
                businessMessage,
                data,
                isEmptyData(dataResult.value())
        );
    }

    /**
     * 加载响应绑定配置。
     */
    private ResponseBindingSpec loadResponseBinding(
            CapabilityDefinition capability,
            boolean allowMissingBindingForDraft) {

        if (capability == null) {
            throw new CapabilityInvocationException(
                    "CAPABILITY_DEFINITION_MISSING",
                    "能力定义不能为空"
            );
        }

        String bindingJson =
                capability.getResponseBindingJson();

        /*
         * 管理端草稿测试需要查看原始返回，
         * 因此允许草稿暂时不配置responseBindingJson。
         */
        if (!StringUtils.hasText(bindingJson)
                && allowMissingBindingForDraft) {

            return ResponseBindingSpec.builder()
                    .version(
                            ResponseBindingSpecParser
                                    .SUPPORTED_VERSION
                    )
                    .dataPath("$")
                    .dataRequired(false)
                    .build();
        }

        if (!StringUtils.hasText(bindingJson)) {
            throw new CapabilityInvocationException(
                    "CAPABILITY_RESPONSE_BINDING_MISSING",
                    "能力缺少响应绑定配置：" +
                            capability.getCapabilityCode()
            );
        }

        try {
            return responseBindingSpecParser.parse(
                    bindingJson
            );
        } catch (BusinessException exception) {
            /*
             * 正式运行阶段不直接暴露整段错误配置。
             * 管理端保存、发布时会返回更具体的校验信息。
             */
            throw new CapabilityInvocationException(
                    "CAPABILITY_RESPONSE_BINDING_INVALID",
                    "能力响应绑定配置无效：" +
                            capability.getCapabilityCode(),
                    exception
            );
        }
    }

    private String readBusinessMessage(
            JsonNode root,
            String messagePath) {

        if (!StringUtils.hasText(messagePath)) {
            return null;
        }

        SimpleJsonPathReader.ReadResult result =
                jsonPathReader.read(
                        root,
                        messagePath
                );

        if (!isReadableScalar(result)) {
            return null;
        }

        return sanitizeMessage(
                result.value().asText()
        );
    }

    private boolean isReadableScalar(
            SimpleJsonPathReader.ReadResult result) {

        return result != null
                && result.found()
                && result.value() != null
                && !result.value().isNull()
                && result.value().isValueNode();
    }

    /**
     * 比较响应值和配置值。
     *
     * 数字200可以匹配字符串"200"；
     * 数字200.0也可以匹配数字200。
     */
    private boolean scalarEquals(JsonNode actual,JsonNode expected) {
        if (actual == null || expected == null || actual.isNull() || expected.isNull()) {
            return false;
        }

        if (actual.isNumber()
                || expected.isNumber()) {

            BigDecimal actualNumber =
                    parseNumber(actual);

            BigDecimal expectedNumber =
                    parseNumber(expected);

            if (actualNumber != null && expectedNumber != null) {
                return actualNumber.compareTo(expectedNumber ) == 0;
            }
        }
        return actual.asText().trim()
                .equals(expected.asText().trim());
    }

    private BigDecimal parseNumber(
            JsonNode value) {
        try {
            if (value.isNumber()) {
                return value.decimalValue();
            }

            if (value.isTextual()) {
                return new BigDecimal(
                        value.asText().trim()
                );
            }
        } catch (NumberFormatException ignored) {
            // 不是数字时回退到普通字符串比较。
        }
        return null;
    }

    private String safeScalarText(
            JsonNode value,
            int maxLength) {

        String text = value.asText();

        if (text == null) {
            return null;
        }

        text = text.trim();

        return text.length() <= maxLength
                ? text
                : text.substring(0, maxLength);
    }

    /**
     * 业务消息可能进入运行轨迹或返回前端，
     * 因此删除换行和制表符并限制长度。
     */
    private String sanitizeMessage(
            String message) {

        if (!StringUtils.hasText(message)) {
            return null;
        }

        String sanitized = message
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();

        return sanitized.length()
                <= MAX_MESSAGE_LENGTH
                ? sanitized
                : sanitized.substring(
                        0,
                        MAX_MESSAGE_LENGTH
                );
    }

    /**
     * null、空数组、空对象、空字符串都属于空数据。
     *
     * 但空数组和空对象不属于执行失败。
     */
    private boolean isEmptyData(
            JsonNode value) {

        if (value == null || value.isNull()) {
            return true;
        }

        if (value.isContainerNode()) {
            return value.size() == 0;
        }

        return value.isTextual()
                && !StringUtils.hasText(
                        value.asText()
                );
    }
}