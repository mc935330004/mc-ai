package org.example.ai.agent.tool;

import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.RoutePlan;

import java.util.List;

/**
 * 工具执行器。
 *
 * 负责执行 RoutePlan 中的工具步骤。
 * 第一版重点处理 BUSINESS_TOOL，RAG 和 LLM_SUMMARY 可以后续再接。
 */
public interface ToolExecutor {

    /**
     * 执行单个计划步骤。
     *
     * @param context 执行上下文
     * @param step 当前计划步骤
     * @return 当前步骤执行结果
     */
    ToolResult execute(ToolExecutionContext context, PlanStep step);

    /**
     * 执行完整运行计划。
     *
     * @param context 执行上下文
     * @param routePlan 运行计划
     * @return 所有步骤执行结果
     */
    List<ToolResult> executePlan(ToolExecutionContext context, RoutePlan routePlan);
}