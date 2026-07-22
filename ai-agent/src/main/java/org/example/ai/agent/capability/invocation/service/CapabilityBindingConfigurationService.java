package org.example.ai.agent.capability.invocation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.CapabilityRequestContractValidator;
import org.example.ai.agent.capability.invocation.RequestBindingSpecParser;
import org.example.ai.agent.capability.invocation.ResponseBindingSpecParser;
import org.example.ai.agent.capability.invocation.model.RequestBindingSpec;
import org.example.ai.agent.capability.invocation.model.ResponseBindingSpec;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 能力请求和响应绑定配置服务。
 *
 * 职责：
 * 1. 保存前规范化绑定 JSON；
 * 2. 校验请求绑定基础语法；
 * 3. 校验 inputSchemaJson 与 requestBindingJson 一致；
 * 4. 发布前重新校验数据库中的绑定配置；
 * 5. 草稿允许绑定配置暂时为空。
 */
@Service
@RequiredArgsConstructor
public class CapabilityBindingConfigurationService {

    private final RequestBindingSpecParser requestBindingSpecParser;
    private final ResponseBindingSpecParser responseBindingSpecParser;
    private final CapabilityRequestContractValidator
            requestContractValidator;
    private final ObjectMapper objectMapper;

    /**
     * 规范化并校验请求绑定。
     *
     * 草稿阶段允许 requestBindingJson 为空；
     * 只要配置了请求绑定，就必须立即完成以下校验：
     *
     * 1. 请求绑定 JSON 结构合法；
     * 2. HTTP 参数目标合法；
     * 3. $input 字段存在于 inputSchemaJson。
     */
    public String normalizeRequestBinding(
            String capabilityCode,
            String method,
            String capabilityUrl,
            String inputSchemaJson,
            String requestBindingJson) {

        if (!StringUtils.hasText(requestBindingJson)) {
            return null;
        }

        /*
         * 第一步：使用现有解析器完成绑定协议、
         * HTTP方法、来源表达式和目标位置校验。
         */
        RequestBindingSpec spec =
                requestBindingSpecParser.parse(
                        method,
                        capabilityUrl,
                        requestBindingJson
                );

        /*
         * 第二步：校验所有 $input.xxx
         * 都存在于 inputSchemaJson。
         */
        requestContractValidator.validate(
                capabilityCode,
                inputSchemaJson,
                spec
        );

        try {
            /*
             * 保存解析器规范化后的强类型配置，
             * 避免数据库中长期存在格式不一致的 JSON。
             */
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
     */
    public String normalizeResponseBinding(
            String responseBindingJson) {

        if (!StringUtils.hasText(responseBindingJson)) {
            return null;
        }

        ResponseBindingSpec spec =
                responseBindingSpecParser.parse(
                        responseBindingJson
                );

        try {
            return objectMapper.writeValueAsString(spec);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "响应绑定配置序列化失败"
            );
        }
    }

    /**
     * 能力发布前重新校验数据库中的完整配置。
     *
     * 不能因为保存草稿时已经校验过，
     * 就跳过发布校验，因为配置可能被迁移脚本或其他服务修改。
     */
    public void validateForPublish(
            CapabilityDefinition capability) {

        if (!StringUtils.hasText(
                capability.getRequestBindingJson())) {

            throw new BusinessException(
                    400,
                    "能力发布前必须配置requestBindingJson："
                            + capability.getCapabilityCode()
            );
        }

        if (!StringUtils.hasText(
                capability.getResponseBindingJson())) {

            throw new BusinessException(
                    400,
                    "能力发布前必须配置responseBindingJson："
                            + capability.getCapabilityCode()
            );
        }

        /*
         * 发布时再次校验：
         * inputSchemaJson 与 requestBindingJson
         * 必须保持一致。
         */
        normalizeRequestBinding(
                capability.getCapabilityCode(),
                capability.getMethod(),
                capability.getUrl(),
                capability.getInputSchemaJson(),
                capability.getRequestBindingJson()
        );

        normalizeResponseBinding(
                capability.getResponseBindingJson()
        );
    }
}