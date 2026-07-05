package org.example.ai.agent.modules.knowledgebase.dto;

import java.time.LocalDateTime;

/**
 * 知识库列表项DTO
 * 使用MapStruct进行转换，见KnowledgeBaseMapper
 */
public record KnowledgeBaseListItemDTO(
        Long id,
        String name,
        String category,
        String originalFilename,
        Long fileSize,
        String contentType,
        LocalDateTime uploadedAt,
        String vectorStatus,
        String vectorError,
        Integer chunkCount
) {
}

