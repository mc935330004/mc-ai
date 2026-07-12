package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 业务系统保存参数。
 */
@Data
public class BusinessSystemSaveDTO {

    /**
     * 修改时传入，新增时为空。
     */
    private Long id;

    /**
     * 业务系统编码。
     */
    @NotBlank(message = "业务系统编码不能为空")
    private String systemCode;

    /**
     * 业务系统名称。
     */
    @NotBlank(message = "业务系统名称不能为空")
    private String systemName;

    /**
     * 业务系统基础地址。
     */
    @NotBlank(message = "业务系统基础地址不能为空")
    private String baseUrl;

    /**
     * OpenAPI 文档地址。
     */
    private String openapiUrl;

    /**
     * 认证方式：FORWARD/NONE。
     */
    private String authType;

    /**
     * 是否启用。
     */
    private Integer enabled;

    /**
     * 备注。
     */
    private String remark;
}