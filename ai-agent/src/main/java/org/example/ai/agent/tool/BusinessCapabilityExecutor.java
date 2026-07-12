package org.example.ai.agent.tool;

import org.example.ai.agent.plan.PlanStep;

/**
 * 业务能力执行器。
 *
 * 只处理 stepType = BUSINESS_TOOL 的步骤。
 * 它不负责规划，也不负责总结答案，只负责安全调用真实业务接口。
 */
public interface BusinessCapabilityExecutor {

    /**
     * 执行业务能力。
     *
     * @param context 执行上下文
     * @param step 当前业务工具步骤
     * @return 工具统一返回结果
     */
    ToolResult execute(ToolExecutionContext context, PlanStep step);

    /**
     * 执行已经通过用户确认的 WRITE 能力。
     *
     * 该入口只能由 PendingActionService 调用。
     */
    ToolResult executeConfirmedWrite(ToolExecutionContext context, PlanStep step,String idempotencyKey);

    /**
     * 管理端测试 READ 能力。
     *
     * 允许测试 DRAFT 和 disabled 能力，
     * 但禁止测试 WRITE 和 DANGEROUS。
     */
    ToolResult executeReadTest(ToolExecutionContext context,PlanStep step);
}