package org.example.ai.agent.capability.version.model;

/**
 * 规范化快照及其校验和。
 */
public record CapabilitySnapshotMaterial(
        String snapshotJson,
        String configChecksum
) {
}