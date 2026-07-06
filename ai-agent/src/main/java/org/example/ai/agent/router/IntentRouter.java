package org.example.ai.agent.router;

import org.example.ai.agent.chat.entity.AgentRequest;

/**
 * 意图路由器接口。
 *
 * 作用：
 * 判断用户问题应该走哪条处理链路。
 *
 * 第一版实现：
 * 使用规则关键词路由。
 *
 * 后续扩展：
 * 可以增加 LLMIntentRouter，用大模型做 JSON 分类。
 */
public interface IntentRouter {

    /**
     * 根据用户请求判断路由类型。
     *
     * @param request Agent 聊天请求
     * @return 路由判断结果
     */
    IntentResult route(AgentRequest request);
}