package org.example.airag.modules.knowledgebase.dto;

public record VectorStatusDTO (


        Long id, // 知识库ID
        String vectorStatus, // 向量化状态
        String vectorError, // 向量化错误信息
        Integer chunkCount   // 向量化的块数
){}
