package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 根据能力输出 Schema 批量生成字段字典。
 */
@Data
public class FieldDictionarySchemaGenerateDTO {

    /**
     * 需要生成字段字典的能力编码。
     */
    @NotEmpty(message = "能力编码列表不能为空")
    private List<String> capabilityCodes;
}