package org.example.ai.agent.capability.invocation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个能力的完整请求绑定配置。
 *
 * method、url、contentType 等信息继续由 CapabilityDefinition 保存，
 * 本对象只负责参数绑定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class RequestBindingSpec {

    /**
     * 请求绑定协议版本。
     *
     * 第一版固定为 1.0。
     */
    private String version;

    /**
     * 全部参数绑定。
     */
    @Builder.Default
    private List<ParameterBindingSpec> parameters = new ArrayList<>();
}