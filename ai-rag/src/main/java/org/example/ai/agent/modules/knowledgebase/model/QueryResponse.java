package org.example.ai.agent.modules.knowledgebase.model;

import java.util.List;

/**
 * 知识库查询响应。
 */
public record QueryResponse(
        /**
         * 查询结果。
         */
        String answer,

        /**
         * 知识库 ID。
         */
        Long knowledgeBaseId,

        /**
         * 知识库名称。
         */
        String knowledgeBaseName,

        /**
         * 本次回答命中的知识库片段来源。
         */
        List<Reference> references
) {
    /**
     * 兼容旧构造方式。
     */
    public QueryResponse(String answer, Long knowledgeBaseId, String knowledgeBaseName) {
        this(answer, knowledgeBaseId, knowledgeBaseName, List.of());
    }

    /**
     * 知识库引用来源。
     */
    public record Reference(
            String source,
            String chunkIndex
    ) {
    }
}