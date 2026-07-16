package org.example.ai.agent.capability.invocation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力响应绑定配置。
 *
 * 用于描述：
 * 1. 如何判断业务接口是否成功；
 * 2. 从哪里读取业务错误消息；
 * 3. 从哪里提取真正的业务数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ResponseBindingSpec {

    /**
     * 协议版本，第一版固定为1.0。
     */
    private String version;

    /**
     * 业务状态码路径，例如：$.code。
     *
     * 不配置时，不使用业务状态码判断成功。
     */
    private String businessCodePath;

    /**
     * 成功状态码集合。
     *
     * 同时兼容数字200和字符串"200"。
     */
    @Builder.Default
    private List<JsonNode> successValues = new ArrayList<>();

    /**
     * 成功标记路径，例如：$.success。
     */
    private String successFlagPath;

    /**
     * 成功标记期望值。
     *
     * 配置successFlagPath但没有填写本字段时，默认值为true。
     */
    private JsonNode successFlagValue;

    /**
     * 业务消息路径，例如：$.message。
     */
    private String messagePath;

    /**
     * 业务数据路径。
     *
     * 默认$，表示整个响应就是业务数据。
     */
    @Builder.Default
    private String dataPath = "$";

    /**
     * 是否要求dataPath对应的节点必须存在且不为null。
     *
     * 空数组和空对象仍然视为有效数据。
     */
    @Builder.Default
    private boolean dataRequired = true;
}