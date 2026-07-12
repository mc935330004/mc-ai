package org.example.ai.agent.trace.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.model.RunStatus;
import org.example.ai.agent.router.RouteType;
import org.example.ai.agent.trace.entity.RunTrace;
import org.example.ai.agent.trace.mapper.RunTraceMapper;
import org.example.ai.agent.trace.service.RunTraceService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Agent 运行主记录 Service 实现。
 */
@Service
@RequiredArgsConstructor
public class RunTraceServiceImpl implements RunTraceService {

    private final RunTraceMapper runTraceMapper;

    @Override
    public void startRun(String runId, AgentRequest request) {
        RunTrace trace = new RunTrace();
        trace.setRunId(runId);
        trace.setConversationId(request.getConversationId());
        trace.setUserId(request.getUserId());
        trace.setQuestion(request.getUserQuestion());
        trace.setStatus(RunStatus.RUNNING);
        trace.setCreatedAt(LocalDateTime.now());
        trace.setUpdatedAt(LocalDateTime.now());
        // 插入主运行记录。
        runTraceMapper.insert(trace);
    }

    @Override
    public void updateRouteType(String runId, RouteType routeType) {
        RunTrace trace = new RunTrace();
        trace.setRouteType(routeType == null ? null : routeType.name());
        // 根据 run_id 更新路由类型。
        runTraceMapper.update(
                trace,
                new LambdaUpdateWrapper<RunTrace>()
                        .eq(RunTrace::getRunId, runId)
        );
    }

    @Override
    public void markSuccess(String runId, long totalDurationMs) {
        RunTrace trace = new RunTrace();
        trace.setStatus(RunStatus.SUCCESS);
        trace.setTotalDurationMs(totalDurationMs);

        // 标记整次运行成功。
        runTraceMapper.update(trace, new LambdaUpdateWrapper<RunTrace>().eq(RunTrace::getRunId, runId));
    }

    @Override
    public void markFailed(String runId, long totalDurationMs, String errorMessage) {
        RunTrace trace = new RunTrace();
        trace.setStatus(RunStatus.FAILED);
        trace.setErrorMessage(errorMessage);
        trace.setTotalDurationMs(totalDurationMs);

        // 标记整次运行失败。
        runTraceMapper.update(trace, new LambdaUpdateWrapper<RunTrace>().eq(RunTrace::getRunId, runId));
    }
}