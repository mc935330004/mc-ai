package org.example.ai.agent.capability.version.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 能力发布快照协议。
 *
 * 只保存会影响：
 * 1. Agent 能力选择；
 * 2. HTTP 请求构建；
 * 3. 响应数据解释；
 * 4. 权限与副作用控制
 * 的配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class CapabilityPublishedSnapshot {

    @Builder.Default
    private String specVersion = "1.0";

    private String capabilityCode;
    private String capabilityName;
    private String domain;
    private String moduleName;
    private String description;

    private String systemCode;
    private String method;
    private String url;
    private String requestContentType;
    private Integer timeoutMs;

    private String sideEffect;
    private Boolean requireConfirm;

    /**
     * 使用 JsonNode 保存，避免把 JSON 当作转义字符串再次嵌套。
     */
    private JsonNode inputSchema;
    private JsonNode outputSchema;
    private JsonNode requestBinding;
    private JsonNode responseBinding;
    private JsonNode example;

    private String sourceType;
    private String sourceOperationId;
}