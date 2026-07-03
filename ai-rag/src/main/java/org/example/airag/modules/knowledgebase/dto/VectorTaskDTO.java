package org.example.airag.modules.knowledgebase.dto;

import java.time.LocalDateTime;

public record VectorTaskDTO(
        Long id, // 任务ID
        Long knowledgeBaseId, // 关联的知识库ID
        String taskType, // 任务类型
        String status, // 任务状态
        Integer retryCount, // 重试次数
        Integer maxRetryCount, // 最大重试次数
        String lockOwner, // 锁定者
        LocalDateTime lockedAt, // 锁定时间
        String errorMessage, // 错误信息
        LocalDateTime createdAt, // 创建时间
        LocalDateTime updatedAt,  // 更新时间
        LocalDateTime startedAt, // 开始时间
        LocalDateTime finishedAt // 结束时间
) {
}