package org.example.ai.agent.capability.version.model;

import org.example.ai.agent.capability.entity.CapabilityVersion;

/**
 * 单个能力版本发布结果。
 */
public record CapabilityVersionPublishResult( CapabilityVersion version, boolean reused) {
}