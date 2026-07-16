package org.example.ai.agent.trace.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.model.RunStatus;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.trace.entity.RunStep;
import org.example.ai.agent.trace.mapper.RunStepMapper;
import org.example.ai.agent.trace.service.RunStepRecorder;
import org.springframework.stereotype.Service;

/**
 * Agent 步骤记录器实现。
 */
@Service
@RequiredArgsConstructor
public class RunStepRecorderImpl implements RunStepRecorder {

    private final RunStepMapper runStepMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void recordSuccess(String runId, PlanStep step, Object input, Object output, long durationMs) {
        RunStep runStep = buildBaseStep(runId, step, input, durationMs);
        runStep.setStatus(RunStatus.SUCCESS);
        runStep.setOutputJson(toJson(output));

        // 写入成功步骤记录。
        runStepMapper.insert(runStep);
    }

    @Override
    public void recordFailed(String runId, PlanStep step, Object input, String errorMessage, long durationMs) {
        RunStep runStep = buildBaseStep(runId, step, input, durationMs);
        runStep.setStatus(RunStatus.FAILED);
        runStep.setErrorMessage(errorMessage);

        // 写入失败步骤记录。
        runStepMapper.insert(runStep);
    }

    /**
     * 构建步骤基础信息。
     */
    private RunStep buildBaseStep(String runId, PlanStep step, Object input, long durationMs) {
        RunStep runStep = new RunStep();
        runStep.setRunId(runId);
        runStep.setStepNo(step.getStepNo());
        runStep.setStepType(step.getStepType() == null ? null : step.getStepType().name());
        runStep.setStepName(step.getStepName());
        runStep.setCapabilityCode(step.getCapabilityCode());
        runStep.setInputJson(toJson(input));
        runStep.setDurationMs(durationMs);
        runStep.setNodeId(step.getNodeId());
        runStep.setExecutionPath(step.getExecutionPath());
        return runStep;
    }

    /**
     * 对象转 JSON。
     *
     * 转换失败时不抛异常，避免 Trace 写入影响主流程。
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"jsonError\":\"序列化失败\"}";
        }
    }
}