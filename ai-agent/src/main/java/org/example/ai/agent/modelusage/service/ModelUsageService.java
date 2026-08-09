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
    void recordFailure(
            ModelCallContext context,
            String provider,
            String modelName,
            TokenUsageData usage,
            long durationMs,
            String errorCategory,
            String errorMessage
    );

    /**
     * 兼容连接测试等不需要失败分类的调用。
     */
    default void recordFailure(
            ModelCallContext context,
            String provider,
            String modelName,
            TokenUsageData usage,
            long durationMs,
            String errorMessage) {

        recordFailure(
                context,
                provider,
                modelName,
                usage,
                durationMs,
                null,
                errorMessage
        );
    }

    /**
     * 查询当前用户某次 Agent 运行的模型使用记录。
     */
    List<ModelUsageRecord> listByRunIdAndUserId( String runId,String userId);

    /**
     * 查询本次运行最后一个成功的用户可见回答模型。
     *
     * 没有匹配记录时返回 null，由调用方回退到用户选择的模型。
     */
    String findLatestSuccessfulAnswerModelCode(
            String runId,
            String userId
    );
}