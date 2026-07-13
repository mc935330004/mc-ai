package org.example.ai.agent.modelusage.service;

import org.example.ai.agent.modelusage.model.ModelCallContext;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * 带 Token、耗时和异常统计的统一模型调用入口。
 */
public interface TrackedChatClientService {

    /**
     * 执行一次同步模型调用，并自动记录 Token。
     *
     * @param context 模型调用上下文
     * @param systemPrompt 系统提示词
     * @param userPrompt 用户提示词
     * @return 完整模型响应
     */
    ChatResponse call( ModelCallContext context,String systemPrompt,String userPrompt);
}