package org.example.ai.agent.capability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量确认保存字段字典请求。
 */
@Data
public class FieldDictionaryBatchConfirmDTO {

    @NotBlank(message = "能力编码不能为空")
    private String capabilityCode;

    @Valid
    @NotEmpty(message = "至少选择一个字段")
    private List<FieldDictionaryCandidateDTO> fields;
}