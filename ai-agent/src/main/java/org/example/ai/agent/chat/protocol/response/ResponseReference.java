package org.example.ai.agent.chat.protocol.response;

/**
 * AI回答使用的知识库引用信息。
 *
 * 只保存前端展示引用需要的字段，
 * 不透传完整文档实体和文本切片实体。
 */
public record ResponseReference(
        String referenceId,
        String documentId,
        String documentVersionId,
        String chunkId,
        String title,
        String sourceUrl,
        String excerpt) {

    public ResponseReference {
        referenceId = ResponseSupport.requireText(
                referenceId,
                "引用referenceId不能为空"
        );

        documentId = ResponseSupport.normalizeText(documentId);
        documentVersionId = ResponseSupport.normalizeText(documentVersionId);
        chunkId = ResponseSupport.normalizeText(chunkId);
        title = ResponseSupport.normalizeText(title);
        sourceUrl = ResponseSupport.normalizeText(sourceUrl);
        excerpt = ResponseSupport.normalizeText(excerpt);
    }
}