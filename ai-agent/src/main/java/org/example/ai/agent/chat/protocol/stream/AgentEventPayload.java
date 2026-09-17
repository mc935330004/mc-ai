package org.example.ai.agent.chat.protocol.stream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 非正文业务事件载荷。
 *
 * 工作流结果和报告追问共用该结构，
 * 避免为每个简单事件重复定义载荷对象。
 */
public record AgentEventPayload(
        String content,
        Map<String, Object> data) {

    public AgentEventPayload {
        content = content == null ? "" : content;
        data = data == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }
}