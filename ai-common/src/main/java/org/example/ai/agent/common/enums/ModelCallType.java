package org.example.ai.agent.common.enums;

/**
 * 大模型调用类型。
 *
 * 作用：
 * 1. 区分一次聊天中不同阶段的模型调用。
 * 2. 方便统计规划、回答、RAG 分别消耗多少 Token。
 * 3. 避免在业务代码中散落字符串常量。
 */
public enum ModelCallType {

    /**
     * 动态能力规划。
     */
    PLANNER,

    /**
     * 最终业务回答。
     */
    ANSWER,

    /**
     * 知识库 RAG 回答。
     */
    RAG,

    /**
     * 字段语义生成。
     */
    FIELD_SEMANTIC,

    /**
     * 回答格式或内容修复。
     */
    REPAIR,

    /**
     * 直接聊天。
     */
    DIRECT_CHAT
}