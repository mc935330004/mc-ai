package org.example.ai.agent.plan;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.router.RouteType;

import java.util.List;

/**
 * Agent 运行计划。
 *
 * 它表示一次用户问题被拆解后的完整执行方案。
 *
 * 注意：
 * RoutePlan 只描述“要做什么”，不负责真正执行。
 * 真正执行放到后面的 ToolExecutor / RagToolExecutor / AnswerComposer。
 */
@Data
@Builder
public class RoutePlan {

    /**
     * 本次运行 ID。
     *
     * 用于串联 Trace、Step、ToolCallLog。
     */
    private String runId;

    /**
     * 路由类型。
     *
     * 来自 IntentRouter 的判断结果。
     */
    private RouteType routeType;

    /**
     * 用户原始问题。
     */
    private String userQuestion;

    /**
     * 本次计划目标。
     *
     * 用自然语言说明这次 Agent 准备完成什么事情。
     */
    private String goal;

    /**
     * 计划步骤列表。
     *
     * 第一版先生成步骤并返回给前端，
     * 暂时不真正执行 BUSINESS_TOOL。
     */
    private List<PlanStep> steps;
}