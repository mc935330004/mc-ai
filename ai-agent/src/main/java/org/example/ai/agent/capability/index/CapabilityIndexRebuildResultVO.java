package org.example.ai.agent.capability.index;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 能力向量索引重建结果。
 */
@Data
@Builder
public class CapabilityIndexRebuildResultVO {

    private int totalCount;

    private int successCount;

    private int failedCount;

    private List<String> failedCapabilityCodes;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}