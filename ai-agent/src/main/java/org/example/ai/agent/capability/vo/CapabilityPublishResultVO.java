package org.example.ai.agent.capability.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 能力批量发布结果。
 */
@Data
@Builder
public class CapabilityPublishResultVO {

    private Integer submittedCount;
    private Integer publishedCount;

    /**
     * 本次成功发布的能力编码。
     */
    private List<String> capabilityCodes;

    private Integer createdVersionCount;
    private Integer reusedVersionCount;
    private List<CapabilityPublishedVersionVO> versions;
}