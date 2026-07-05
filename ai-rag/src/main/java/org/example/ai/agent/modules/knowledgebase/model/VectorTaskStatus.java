package org.example.ai.agent.modules.knowledgebase.model;

public enum VectorTaskStatus {
    /**
     * 待处理
     */
    PENDING,
    /**
     * 处理中
     */
    PROCESSING,
    /**
     * 成功
     */
    COMPLETED,

    /**
     * 失败
     */
    FAILED,
    /**
     * 取消
     */
    CANCELED
}