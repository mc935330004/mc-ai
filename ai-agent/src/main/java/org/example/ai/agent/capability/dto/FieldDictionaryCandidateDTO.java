package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 用户从真实响应中选择保存的字段。
 *
 * 不提供id和capabilityCode，避免前端借批量接口修改其他字段。
 */
@Data
public class FieldDictionaryCandidateDTO {

    @NotBlank(message = "字段路径不能为空")
    private String fieldPath;

    @NotBlank(message = "字段名称不能为空")
    private String fieldName;

    private String fieldCnName;

    private String fieldType;

    private String businessMeaning;

    private String displayFormat;

    private String exampleValue;

    private Integer searchable;

    private Integer aggregatable;
}