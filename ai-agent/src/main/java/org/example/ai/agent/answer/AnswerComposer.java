package org.example.ai.agent.answer;

import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.plan.RoutePlan;
import org.example.ai.agent.tool.ToolResult;

import java.util.List;

/**
 * 答案组装器。
 *
 * 作用：
 * 把业务工具返回的结构化数据，转换成用户能看懂的自然语言回答。
 */
public interface AnswerComposer {

    /**
     * 基于业务工具结果生成最终回答。
     *
     * @param request 用户原始请求
     * @param routePlan 本次运行计划
     * @param toolResults 工具执行结果
     * @return 最终回答文本
     */
    String compose(AgentRequest request, RoutePlan routePlan, List<ToolResult> toolResults);
}