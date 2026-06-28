package org.example.airag.modules.knowledgebase.dto;

/**
 * 知识库统计 DTO。
 *
 * <p>参考 interview-guide 的 KnowledgeBaseStatsDTO，
 * 当前项目先统计 knowledge_base 表中已经存在的字段。</p>
 */
public record KnowledgeBaseStatsDTO(
        Long totalCount,
        Long completedCount,
        Long processingCount,
        Long failedCount,
        Long pendingCount,
        Long totalFileSize,
        Long totalChunkCount,
        Long categoryCount
) {
}