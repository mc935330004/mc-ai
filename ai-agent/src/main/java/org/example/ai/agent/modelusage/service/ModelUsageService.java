package org.example.ai.agent.modelusage.service;

import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.modelusage.entity.ModelUsageRecord;
import org.example.ai.agent.modelusage.model.TokenUsageData;

import java.util.List;

/**
 * 大模型 Token 使用记录 Service。
 */
public interface ModelUsageService {

    /**
     * 保存一次成功的模型调用，并汇总到 ai_run_trace。
     */
    void recordSuccess(ModelCallContext context, String provider, String modelName, String requestId,
                       TokenUsageData usage, long durationMs, String finishReason );

    /**
     * 保存一次失败的模型调用。
     */
    void recordFailure(ModelCallContext context,String provider,String modelName,TokenUsageData usage,
                       long durationMs, String errorMessage);

    /**
     * 查询当前用户某次 Agent 运行的模型使用记录。
     */
    List<ModelUsageRecord> listByRunIdAndUserId( String runId,String userId);
}