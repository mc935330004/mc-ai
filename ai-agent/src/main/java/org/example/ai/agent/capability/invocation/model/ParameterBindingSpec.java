package org.example.ai.agent.capability.invocation.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个 HTTP 参数的绑定配置。
 *
 * 一个绑定负责描述：
 * 1. 参数值从哪里获取；
 * 2. 参数最终放到 HTTP 请求的哪个位置；
 * 3. 参数是否必填、是否敏感。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public class ParameterBindingSpec {

    /**
     * 参数来源类型。
     */
    private ParameterSourceType sourceType;

    /**
     * 参数来源表达式。
     *
     * sourceType=FIXED 时必须为空。
     *
     * 示例：
     * $input.projectName
     * $vars.project.id
     * $item
     * $item.projectName
     * $secure.organizationId
     */
    private String sourceExpression;

    /**
     * 参数目标位置。
     */
    private ParameterTargetLocation targetLocation;

    /**
     * PATH 或 QUERY 参数名称。
     *
     * 示例：
     * projectId
     * keyword
     *
     * targetLocation=BODY 时使用 targetPath，不使用该字段。
     */
    private String targetName;

    /**
     * BODY 中的目标路径。
     *
     * 当前采用受限路径语法：
     * $               表示整个请求体
     * $.projectId     表示一级字段
     * $.project.id    表示嵌套字段
     */
    private String targetPath;

    /**
     * 固定值。
     *
     * 仅在 sourceType=FIXED 时使用。
     * 使用 JsonNode 是为了保留字符串、数字、布尔值、数组和对象类型。
     */
    private JsonNode fixedValue;

    /**
     * 默认值。
     *
     * 非必填参数的来源值为空时，可以使用默认值。
     */
    private JsonNode defaultValue;

    /**
     * 是否为必填参数。
     */
    private boolean required;

    /**
     * 当最终值为空时是否省略该参数。
     */
    @Builder.Default
    private boolean omitIfNull = true;

    /**
     * 是否为敏感参数。
     *
     * 敏感参数不能输出到日志或运行轨迹。
     */
    private boolean sensitive;
}