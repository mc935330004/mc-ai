package org.example.ai.agent.chat.protocol.stream;

/**
 * SSE连接心跳数据。
 *
 * lastSequence用于前端判断当前已经接收到的
 * 最后一个有效业务事件序号。
 */
public record HeartbeatPayload(
        long lastSequence) {

    public HeartbeatPayload {
        lastSequence = StreamSupport.normalizeSequence(lastSequence);
    }
}