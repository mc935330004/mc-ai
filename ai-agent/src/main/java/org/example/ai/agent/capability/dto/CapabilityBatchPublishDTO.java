package org.example.ai.agent.capability.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 能力批量发布请求。
 */
@Data
public class CapabilityBatchPublishDTO {

    /**
     * 需要发布的能力编码。
     */
    @NotEmpty(message = "至少选择一个能力")
    private List<String> capabilityCodes;
}