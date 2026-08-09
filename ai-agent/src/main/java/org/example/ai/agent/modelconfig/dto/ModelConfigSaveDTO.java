package org.example.ai.agent.modelconfig.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型配置保存参数。
 */
@Data
public class ModelConfigSaveDTO {

    @NotBlank(message = "模型编码不能为空")
    @Pattern(
            regexp = "^[a-z][a-z0-9_-]{1,63}$",
            message = "模型编码只能包含小写字母、数字、下划线和短横线"
    )
    private String modelCode;

    @NotBlank(message = "模型名称不能为空")
    @Size(max = 128, message = "模型名称不能超过128个字符")
    private String displayName;

    @NotBlank(message = "供应商编码不能为空")
    @Pattern(
            regexp = "^[a-z][a-z0-9_-]{1,63}$",
            message = "供应商编码格式不正确"
    )
    private String providerCode;

    @NotBlank(message = "接口类型不能为空")
    private String apiType = "OPENAI_COMPATIBLE";

    @NotBlank(message = "API地址不能为空")
    @Size(max = 512, message = "API地址不能超过512个字符")
    private String baseUrl;

    /**
     * 新增模型时必填。
     *
     * 修改模型时为空表示保留原密钥。
     */
    @Size(max = 512, message = "API Key长度不能超过512个字符")
    private String apiKey;

    @NotBlank(message = "实际模型名称不能为空")
    @Size(max = 128, message = "实际模型名称不能超过128个字符")
    private String modelName;

    @NotNull(message = "temperature不能为空")
    @DecimalMin(value = "0.0", message = "temperature不能小于0")
    @DecimalMax(value = "2.0", message = "temperature不能大于2")
    private BigDecimal temperature = new BigDecimal("0.2");

    @NotNull(message = "最大输出Token不能为空")
    @Min(value = 1, message = "最大输出Token必须大于0")
    @Max(value = 1000000, message = "最大输出Token超出允许范围")
    private Integer maxTokens = 2048;

    @NotNull(message = "请求超时不能为空")
    @Min(value = 3, message = "请求超时不能小于3秒")
    @Max(value = 120, message = "请求超时不能超过120秒")
    private Integer timeoutSeconds = 30;

    @NotNull(message = "是否支持流式输出不能为空")
    private Boolean streamingSupported = true;

    @NotNull(message = "是否支持结构化输出不能为空")
    private Boolean structuredOutputSupported = false;

    @NotNull(message = "是否支持工具调用不能为空")
    private Boolean toolCallingSupported = false;

    @NotNull(message = "上下文窗口不能为空")
    @Min(value = 1, message = "上下文窗口必须大于0")
    private Integer contextWindow = 8192;

    @NotNull(message = "默认模型标识不能为空")
    private Boolean defaultModel = false;

    @NotNull(message = "启用状态不能为空")
    private Boolean enabled = false;

    @NotNull(message = "展示顺序不能为空")
    private Integer sortOrder = 0;

    @Size(max = 500, message = "备注不能超过500个字符")
    private String remark;
}