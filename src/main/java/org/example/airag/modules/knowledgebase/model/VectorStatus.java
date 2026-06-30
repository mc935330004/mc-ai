package org.example.airag.modules.knowledgebase.model;

/**
 * 知识库向量化状态
 */
public enum VectorStatus {
    /**
     * 等待处理
     */
    PENDING,
    /**
     * 处理中
     */
    PROCESSING,
    /**
     * 处理完成
     */
    COMPLETED,
    /**
     * 处理失败
     */
    FAILED
}
