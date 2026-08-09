package org.example.ai.agent.modelconfig.event;

/**
 * 模型配置发生变化事件。
 *
 * 事务提交成功后，由客户端注册表清理对应缓存。
 */
public record ModelConfigChangedEvent(String modelCode) {
}