package org.example.ai.agent.common.enums;

/**
 * 第一版GraphSpec支持的节点类型。
 */
public enum GraphNodeType {

    /**
     * 图入口。
     */
    START,

    /**
     * 调用能力管理中已经发布的能力。
     */
    CAPABILITY,

    /**
     * 遍历集合，最多处理5项。
     */
    FOREACH,

    /**
     * 合并多个节点输出。
     */
    MERGE,

    /**
     * 图结束和最终结果输出。
     */
    END
}