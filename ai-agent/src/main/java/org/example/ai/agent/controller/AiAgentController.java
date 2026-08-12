package org.example.ai.agent.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 旧版模型直连接口。
 *
 * 默认不注册该控制器，仅在紧急兼容旧调用方时临时开启。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/aiAgent")
@ConditionalOnProperty(
        prefix = "app.agent.legacy-direct-chat",
        name = "enabled",
        havingValue = "true"
)
public class AiAgentController {

    private final TrackedChatClientService trackedChatClientService;

    /**
     * ChatClient 流式调用
     */
    @GetMapping("/stream/chat")
    public Flux<String> streamChat(@RequestParam(value = "query", defaultValue = "你好，很高兴认识你，能简单介绍一下自己吗？") String query, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");

        ModelCallContext context = ModelCallContext.builder()
                .callType(ModelCallType.DIRECT_CHAT)
                .callSequence(1)
                .build();

        return trackedChatClientService.stream(
                        context,
                        "",
                        query
                )
                .map(this::extractContent)
                .filter(StringUtils::hasText);
    }

    private String extractContent(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }

        return response.getResult()
                .getOutput()
                .getText();
    }
}
