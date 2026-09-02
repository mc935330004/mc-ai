package org.example.ai.agent.business.snapshot;

/** 快照匹配的安全摘要，不返回查询事实或权限细节。 */
public record SnapshotMatchResult(
        SnapshotMatchDecision decision,
        String snapshotId,
        String reason) {
}
