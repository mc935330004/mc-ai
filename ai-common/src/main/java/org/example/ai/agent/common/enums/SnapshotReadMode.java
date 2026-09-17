package org.example.ai.agent.common.enums;

/**
 * 业务安全快照读取模式。
 */
public enum SnapshotReadMode {

    /** 只复用仍在新鲜期内的快照。 */
    REUSE_IF_FRESH,

    /** 用户明确引用历史结果时，允许读取仍在保留期内的快照。 */
    REUSE_SNAPSHOT,

    /** 用户明确要求最新数据，必须重新查询。 */
    FORCE_LIVE
}