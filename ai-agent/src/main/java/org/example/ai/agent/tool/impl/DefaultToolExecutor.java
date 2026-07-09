package org.example.ai.agent.tool.impl;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.plan.StepType;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolExecutor;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.trace.service.RunStepRecorder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 默认工具执行器。
 *
 * 负责按 RoutePlan 顺序执行步骤。
 * 第一版只真正执行 BUSINESS_TOOL，其它步骤先返回占位结果。
 */
@Service
@RequiredArgsConstructor
public class DefaultToolExecutor implements ToolExecutor {

    private final BusinessCapabilityExecutor businessCapabilityExecutor;
    private final RunStepRecorder runStepRecorder;
    @Override
    public ToolResult execute(ToolExecutionContext context, PlanStep step) {
        // 业务工具步骤交给 BusinessCapabilityExecutor。
        if (step.getStepType() == StepType.BUSINESS_TOOL) {
            return businessCapabilityExecutor.execute(context, step);
        }
        // RAG、LLM_SUMMARY 后续可以接入专门执行器。
        return ToolResult.builder()
                .success(true)
                .input(step.getInput())
                .outputKey(step.getOutputKey())
                .summary("当前步骤暂未接入真实执行器：" + step.getStepType())
                .build();
    }

    @Override
    public List<ToolResult> executePlan(ToolExecutionContext context, RoutePlan routePlan) {
        List<ToolResult> results = new ArrayList<>();
        for (PlanStep step : routePlan.getSteps()) {
            long startTime = System.currentTimeMillis();
            ToolResult result = execute(context, step);
            long durationMs = System.currentTimeMillis() - startTime;
            // 成功和失败都写入 ai_run_step。
            if (result.isSuccess()) {
                runStepRecorder.recordSuccess(context.getRunId(),step,result.getInput(),result,durationMs);
            } else {
                runStepRecorder.recordFailed(context.getRunId(),step,result.getInput(),result.getErrorMessage(),
                        durationMs );
            }
            results.add(result);
            // 执行成功后，把结果写入变量池，供后续 inputRef 使用。
            if (result.isSuccess() && step.getOutputKey() != null) {
                if (context.getVariables() == null) {
                    context.setVariables(new LinkedHashMap<>());
                }
                context.getVariables().put(step.getOutputKey(), result.getData());
            }
            // 如果某个业务步骤失败，第一版建议直接停止，避免后续步骤拿到空参数乱查。
            if (!result.isSuccess()) {
                break;
            }
        }
        return results;
    }
}