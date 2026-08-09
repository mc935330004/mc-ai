package org.example.ai.agent.modelconfig.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型授权保存参数。
 *
 * 保存时完整替换当前对象的模型配置。
 */
@Data
public class ModelAssignmentSaveDTO {

    @Valid
    @NotEmpty(message = "至少需要配置一个模型")
    @Size(max = 20, message = "单个对象最多配置20个模型")
    private List<ModelAssignmentItemDTO> models =new ArrayList<>();
}