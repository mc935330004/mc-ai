package org.example.ai.agent.common.modelusage;

import org.springframework.ai.chat.model.ChatResponse;
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
}