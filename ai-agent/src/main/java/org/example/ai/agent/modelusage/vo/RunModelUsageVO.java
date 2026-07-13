package org.example.ai.agent.modelusage.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.modelusage.entity.ModelUsageRecord;

import java.util.List;

/**
 * 单次 Agent 运行的模型 Token 使用情况。
 */
@Data
@Builder
public class RunModelUsageVO {

    /**
     * Agent 运行 ID。
     */
    private String runId;

    /**
     * 模型调用次数。
     */
    private int modelCallCount;

    /**
     * 累计输入 Token。
     */
    private long promptTokens;

    /**
     * 累计输出 Token。
     */
    private long completionTokens;

    /**
     * 累计总 Token。
     */
    private long totalTokens;

    /**
     * 模型调用明细。
     */
    private List<ModelUsageRecord> calls;
}