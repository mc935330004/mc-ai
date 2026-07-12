package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户选择导入的 OpenAPI 接口。
 *
 * url 和 method 用于重新从 OpenAPI 文档读取接口，
 * 不直接相信前端提交的 Schema。
 */
@Data
public class OpenApiImportItemDTO {

    @NotBlank(message = "接口地址不能为空")
    private String url;

    @NotBlank(message = "请求方法不能为空")
    private String method;

    /**
     * 允许人工修改自动生成的能力编码。
     */
    private String capabilityCode;

    /**
     * 允许人工修改能力名称。
     */
    private String capabilityName;

    /**
     * 允许人工调整 READ 只读查询、WRITE 写操作、DANGEROUS 危险操作。
     */
    private String sideEffect;
}