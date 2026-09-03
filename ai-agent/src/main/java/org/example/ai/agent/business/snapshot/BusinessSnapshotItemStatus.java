package org.example.ai.agent.business.snapshot;

/** V13业务快照执行项允许状态。 */
public enum BusinessSnapshotItemStatus {
    PENDING,
    AUTHORIZING,
    RUNNING,
    SUCCESS,
    NO_DATA,
    FAILED,
    TIMEOUT,
    RESTRICTED,
    SKIPPED
}
