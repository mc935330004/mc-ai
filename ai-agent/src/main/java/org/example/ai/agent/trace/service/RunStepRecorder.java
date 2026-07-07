package org.example.ai.agent.trace.service;

import org.example.ai.agent.plan.PlanStep;

/**
 * Agent 步骤记录器。
 *
 * 只负责写 ai_run_step。
 */
public interface RunStepRecorder {

    /**
     * 记录步骤成功。
     */
    void recordSuccess(String runId, PlanStep step, Object input, Object output, long durationMs);

    /**
     * 记录步骤失败。
     */
    void recordFailed(String runId, PlanStep step, Object input, String errorMessage, long durationMs);
}