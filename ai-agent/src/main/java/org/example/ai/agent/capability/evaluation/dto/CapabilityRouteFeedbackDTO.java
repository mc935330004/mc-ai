package org.example.ai.agent.capability.evaluation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CapabilityRouteFeedbackDTO {

    @NotBlank(message = "runId不能为空")
    private String runId;

    /**
     * true：系统原选择正确
     * false：系统原选择错误
     */
    @NotNull(message = "correct不能为空")
    private Boolean correct;

    /**
     * correct=false 时必填。
     */
    private String expectedCapabilityCode;

    private String comment;
}