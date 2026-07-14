package org.example.ai.agent.common.modelusage;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

/**
 * 带 Token、耗时和异常统计的统一模型调用入口。
 */
public interface TrackedChatClientService {

    /**
     * 执行同步模型调用。
     *
     * 模型调用完成后自动记录 Token、耗时和调用状态。
     */
    ChatResponse call(ModelCallContext context,String systemPrompt,String userPrompt);

    /**
     * 执行流式模型调用。
     *
     * 流正常结束时记录成功；
     * 流异常或被取消时记录失败。
     */
    Flux<ChatResponse> stream( ModelCallContext context,String systemPrompt,String userPrompt);

    /**
     * 使用本次调用专属模型参数执行同步调用。
     *
     * 主要用于 Planner、Router 等需要低随机性的模型调用，
     * 避免修改全局回答模型的 temperature。
     */
    ChatResponse call(ModelCallContext context, String systemPrompt,String userPrompt,ChatOptions.Builder<?> optionsBuilder );
}