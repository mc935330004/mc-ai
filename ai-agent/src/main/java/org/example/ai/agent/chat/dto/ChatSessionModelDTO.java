package org.example.ai.agent.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatSessionModelDTO {

    /**
     *  前端选择的模型编码，必须来自后端配置。
     */
    @NotBlank(message = "模型编码不能为空")
    private String modelCode;
}