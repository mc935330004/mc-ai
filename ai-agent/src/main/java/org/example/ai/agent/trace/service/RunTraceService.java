package org.example.ai.agent.trace.service;

import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.router.RouteType;

/**
 * Agent 运行主记录 Service。
 *
 * 只负责 ai_run_trace 主表。
 */
public interface RunTraceService {

    /**
     * 开始一次 Agent 运行。
     */
    void startRun(String runId, AgentRequest request);

    /**
     * 更新路由类型。
     */
    void updateRouteType(String runId, RouteType routeType);

    /**
     * 标记运行成功。
     */
    void markSuccess(String runId, long totalDurationMs);

    /**
     * 标记运行失败。
     */
    void markFailed(String runId, long totalDurationMs, String errorMessage);

    /**
     * 绑定本次实际执行的工作流版本。
     */
    void bindWorkflow(String runId,String workflowCode, Long workflowVersionId);
}