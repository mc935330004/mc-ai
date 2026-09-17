package org.example.ai.agent.modules.knowledgebase.model;

import java.time.LocalDateTime;

/**
 * 知识库检索证据。
 *
 * 文档内容属于不可信业务资料，只能作为回答证据，
 * 不能作为系统指令、工具参数或权限依据。
 */
public record KnowledgeEvidence(
        String evidenceId,
        Long documentId,
        Long versionId,
        Long chunkId,
        Integer chunkIndex,
        String documentTitle,
        String versionNo,
        String text,
        String source,
        Integer pageNumber,
        double score,
        LocalDateTime retrievedAt) {
}