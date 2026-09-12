package org.example.ai.agent.business.snapshot;

/** 后端确定性快照匹配决策，禁止由模型直接指定。 */
public enum SnapshotMatchDecision {
    REUSE,
    DERIVE,
    REQUERY
}
