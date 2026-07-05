// 文件：src/main/java/org/example/airag/modules/knowledgebase/dto/KnowledgeDocumentQueryResponse.java

package org.example.ai.agent.modules.knowledgebase.dto;

import java.util.List;

/**
 * 企业知识文档问答响应。
 */
public record KnowledgeDocumentQueryResponse(
        String answer,
        List<Reference> references
) {
    /**
     * 引用来源，用于回答溯源。
     */
    public record Reference(
            Long documentId,
            Long versionId,
            Long chunkId,
            String chunkIndex,
            String documentTitle,
            String source
    ) {
    }
}