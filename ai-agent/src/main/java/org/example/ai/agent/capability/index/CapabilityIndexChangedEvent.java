package org.example.ai.agent.capability.index;

/**
 * 能力配置变化事件。
 *
 * 数据库事务提交后，根据最新能力状态决定：
 *
 * 1. 更新向量
 * 2. 删除向量
 */
public record CapabilityIndexChangedEvent( String capabilityCode) {
}