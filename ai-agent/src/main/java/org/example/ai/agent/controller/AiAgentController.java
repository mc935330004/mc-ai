package org.example.ai.agent.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.config.ChatClientConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * AI代理控制器
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/aiAgent")
public class AiAgentController {

    private final ChatClient chatClient;

    /**
     * ChatClient 流式调用
     */
    @GetMapping("/stream/chat")
    public Flux<String> streamChat(@RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？") String query, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        // 构建 ChatOptions
        return chatClient.prompt(query).stream().content();
    }
}
