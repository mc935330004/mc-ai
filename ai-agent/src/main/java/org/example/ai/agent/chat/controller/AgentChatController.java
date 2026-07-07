package org.example.ai.agent.chat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.service.AgentOrchestrator;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.router.IntentRouter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Hashtable;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    private final AgentOrchestrator agentOrchestrator;
    private final RestClient restClient;
    /**
     * 流式聊天
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody AgentRequest request) {
        return agentOrchestrator.chat(request);
    }

    /**
     * 测试外部接口
     */
    @GetMapping("/test")
    public Result<Object> test(String userQuestion) {
        Map<String, Object> params=new Hashtable<>();
        params.put("queryStr", userQuestion);
//        String url = "https://192.168.8.251:9999/pm";
        String url = "/outputMain/page";
        String token="2891c445-38f2-40b6-b3cd-a6c99dadba04";
        // GET 请求使用 query param。
        Object dataResult = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(url);
                    params.forEach(uriBuilder::queryParam);
                    return uriBuilder.build();
                })
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(Object.class);
        return Result.success(dataResult);
    }
}