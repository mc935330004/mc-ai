package org.example.ai.agent.capability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * OpenAPI 批量导入请求。
 */
@Data
public class OpenApiImportRequest {

    @NotBlank(message = "业务系统编码不能为空")
    private String systemCode;

    @Valid
    @NotEmpty(message = "至少选择一个接口")
    private List<OpenApiImportItemDTO> interfaces;
}