package org.example.ai.agent.capability.invocation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.RequestBindingSpecParser;
import org.example.ai.agent.capability.invocation.ResponseBindingSpecParser;
import org.example.ai.agent.capability.invocation.model.RequestBindingSpec;
import org.example.ai.agent.capability.invocation.model.ResponseBindingSpec;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 能力绑定配置服务。
 *
 * 职责：
 * 1. 保存前规范化绑定 JSON；
 * 2. 发布前重新校验数据库中的绑定配置；
 * 3. 草稿允许配置暂时不完整；
 * 4. 发布版本必须具备合法请求绑定。
 */
@Service
@RequiredArgsConstructor
public class CapabilityBindingConfigurationService {

    private final RequestBindingSpecParser requestBindingSpecParser;
    private final ResponseBindingSpecParser responseBindingSpecParser;
    private final ObjectMapper objectMapper;

    /**
     * 规范化请求绑定 JSON。
     *
     * 草稿阶段允许为空；只要填写了，就必须立即通过结构校验。
     */
    public String normalizeRequestBinding(
            String method,
            String capabilityUrl,
            String requestBindingJson) {

        if (!StringUtils.hasText(requestBindingJson)) {
            return null;
        }

        RequestBindingSpec spec = requestBindingSpecParser.parse(
                method,
                capabilityUrl,
                requestBindingJson
        );

        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "请求绑定配置序列化失败"
            );
        }
    }

    /**
     * 规范化响应绑定 JSON。
     *
     * L0-2 只要求它是 JSON 对象；
     * businessCodePath、successValues 等字段的完整校验放到 L0-4。
     */
    public String normalizeResponseBinding( String responseBindingJson) {

        if (!StringUtils.hasText(responseBindingJson)) {
            return null;
        }
        ResponseBindingSpec spec = responseBindingSpecParser.parse( responseBindingJson);
        try {
            return objectMapper.writeValueAsString(spec );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "响应绑定配置序列化失败"
            );
        }
    }

    /**
     * 发布前重新校验数据库中的能力配置。
     *
     * 不能因为保存草稿时已经校验过，就跳过发布校验；
     * 数据可能被脚本、迁移或其他服务修改。
     */
    public void validateForPublish(CapabilityDefinition capability) {

        if (!StringUtils.hasText(capability.getRequestBindingJson())) {
            throw new BusinessException(400, "能力发布前必须配置requestBindingJson：" +
                            capability.getCapabilityCode());
        }

        if (!StringUtils.hasText(
                capability.getResponseBindingJson())) {

            throw new BusinessException(
                    400,
                    "能力发布前必须配置responseBindingJson：" +
                            capability.getCapabilityCode()
            );
        }

        normalizeRequestBinding(
                capability.getMethod(),
                capability.getUrl(),
                capability.getRequestBindingJson()
        );

        normalizeResponseBinding(
                capability.getResponseBindingJson()
        );
    }

    private String safeMessage(
            JsonProcessingException exception) {

        String message = exception.getOriginalMessage();

        if (!StringUtils.hasText(message)) {
            return "JSON格式不正确";
        }

        return message.length() <= 300
                ? message
                : message.substring(0, 300);
    }
}