package org.example.airag.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryLog;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryReference;
import org.example.airag.modules.KnowledgeLog.service.KnowledgeQueryLogService;
import org.example.airag.modules.KnowledgeLog.service.KnowledgeQueryReferenceService;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentQueryRequest;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentQueryResponse;
import org.example.airag.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.airag.modules.knowledgebase.service.KnowledgeChunkService;
import org.example.airag.modules.knowledgebase.service.KnowledgeDocumentService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.springframework.util.StringUtils.truncate;

/**
 * 企业知识文档问答服务。
 *
 * 只面向 knowledge_document 主线，不复用旧 knowledge_base 查询逻辑。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentQueryService {

    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_MIN_SCORE = 0.2;
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识文档中未检索到相关信息。";

    private final KnowledgeDocumentService documentService;
    private final ObjectProvider<KnowledgeBaseVectorService> vectorServiceProvider;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final KnowledgeQueryLogService queryLogService;
    private final KnowledgeQueryReferenceService queryReferenceService;
    private final KnowledgeChunkService chunkService;

    /**
     * 企业文档流式问答。
     *
     * SSE 事件说明：
     * message：模型回答增量文本
     * references：引用来源列表
     * done：流式响应结束标记
     * error：异常信息
     */
    public SseEmitter streamQuery(KnowledgeDocumentQueryRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> doStreamQuery(request, emitter));
        return emitter;
    }

    private void doStreamQuery(KnowledgeDocumentQueryRequest request, SseEmitter emitter) {
        long start = System.currentTimeMillis();
        String question = normalizeQuestion(request);
        int topK = normalizeTopK(request);
        double minScore = normalizeMinScore(request);
        StringBuilder answerBuilder = new StringBuilder();
        try {
            if (!StringUtils.hasText(question)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
            }

            List<Document> hits = retrieveHits(request, question, topK, minScore);
            if (hits.isEmpty()) {
                saveQueryLog(question, NO_RESULT_RESPONSE, topK, minScore, "NO_RESULT", null, start);
                sendEvent(emitter, "message", NO_RESULT_RESPONSE);
                sendEvent(emitter, "references", List.of());
                sendEvent(emitter, "done", "[DONE]");
                emitter.complete();
                return;
            }

            requireChatClient()
                    .prompt()
                    .system(buildSystemPrompt())
                    .user(buildUserPrompt(buildContext(hits), question))
                    .stream()
                    .content()
                    .doOnNext(content -> {
                        if (StringUtils.hasText(content)) {
                            answerBuilder.append(content);
                            sendEvent(emitter, "message", content);
                        }
                    })
                    .doOnError(error -> {
                        saveQueryLog(question, null, topK, minScore, "FAILED", error.getMessage(), start);
                        sendEvent(emitter, "error", truncate(error.getMessage()));
                        emitter.completeWithError(error);
                    })
                    .doOnComplete(() -> {
                        String answer = answerBuilder.toString().trim();
                        KnowledgeQueryLog queryLog = saveQueryLog(
                                question,
                                StringUtils.hasText(answer) ? answer : NO_RESULT_RESPONSE,
                                topK,
                                minScore,
                                "SUCCESS",
                                null,
                                start
                        );
                        saveQueryReferences(queryLog.getId(), hits);
                        sendEvent(emitter, "references", buildReferences(hits));
                        sendEvent(emitter, "done", "[DONE]");
                        emitter.complete();
                    })
                    .blockLast();
        } catch (Exception e) {
            saveQueryLog(question, null, topK, minScore, "FAILED", e.getMessage(), start);
            sendEvent(emitter, "error", truncate(e.getMessage()));
            emitter.completeWithError(e);
        }
    }

    /**
     * 企业文档普通问答。
     */
    public KnowledgeDocumentQueryResponse query(KnowledgeDocumentQueryRequest request) {
        long start = System.currentTimeMillis();
        String question = normalizeQuestion(request);
        int topK = normalizeTopK(request);
        double minScore = normalizeMinScore(request);
        try {
            if (!StringUtils.hasText(question)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
            }

            List<Document> hits = retrieveHits(request, question, topK, minScore);
            if (hits.isEmpty()) {
                saveQueryLog(question, NO_RESULT_RESPONSE, topK, minScore, "NO_RESULT", null, start);
                return new KnowledgeDocumentQueryResponse(NO_RESULT_RESPONSE, List.of());
            }

            String answer = requireChatClient()
                    .prompt()
                    .system(buildSystemPrompt())
                    .user(buildUserPrompt(buildContext(hits), question))
                    .call()
                    .content();

            KnowledgeQueryLog queryLog = saveQueryLog(question, answer, topK, minScore, "SUCCESS", null, start);
            saveQueryReferences(queryLog.getId(), hits);
            return new KnowledgeDocumentQueryResponse(
                    StringUtils.hasText(answer) ? answer.trim() : NO_RESULT_RESPONSE,
                    buildReferences(hits)
            );
        } catch (Exception e) {
            saveQueryLog(question, null, topK, minScore, "FAILED", e.getMessage(), start);
            throw e;
        }
    }

    private List<Document> retrieveHits(KnowledgeDocumentQueryRequest request, String question, int topK, double minScore) {
        List<KnowledgeDocument> documents = findPublishedDocuments(request);
        List<Long> versionIds = documents.stream()
                .map(KnowledgeDocument::getCurrentVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (versionIds.isEmpty()) {
            return List.of();
        }
        List<Document> hits = requireVectorService().similaritySearchByVersionIds(question, versionIds, topK, minScore);
        return filterEnabledChunks(hits);
    }

    /**
     * 查询已发布文档，并只使用 currentVersionId 参与正式问答。
     */
    private List<KnowledgeDocument> findPublishedDocuments(KnowledgeDocumentQueryRequest request) {
        return documentService.lambdaQuery()
                .eq(KnowledgeDocument::getDelFlag, 0)
                .eq(KnowledgeDocument::getStatus, "PUBLISHED")
                .isNotNull(KnowledgeDocument::getCurrentVersionId)
                .in(request.categoryIds() != null && !request.categoryIds().isEmpty(),
                        KnowledgeDocument::getCategoryId, request.categoryIds())
                .in(request.documentIds() != null && !request.documentIds().isEmpty(),
                        KnowledgeDocument::getId, request.documentIds())
                .list();
    }

    private KnowledgeBaseVectorService requireVectorService() {
        KnowledgeBaseVectorService vectorService = vectorServiceProvider.getIfAvailable();
        if (vectorService == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量检索服务未启用");
        }
        return vectorService;
    }

    private ChatClient requireChatClient() {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 对话服务未启用");
        }
        return builder.build();
    }

    private String buildContext(List<Document> documents) {
        return documents.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String buildSystemPrompt() {
        return """
                你是企业知识库问答助手。
                你必须优先根据检索到的企业知识文档回答。
                如果文档内容不足以回答，请明确说明未检索到足够信息。
                不要编造企业知识文档中没有出现的内容。
                """;
    }

    private String buildUserPrompt(String context, String question) {
        return """
                【企业知识文档内容】
                %s

                【用户问题】
                %s
                """.formatted(context, question);
    }

    private List<KnowledgeDocumentQueryResponse.Reference> buildReferences(List<Document> documents) {
        return documents.stream()
                .map(document -> new KnowledgeDocumentQueryResponse.Reference(
                        toLong(metadata(document, "document_id")),
                        toLong(metadata(document, "version_id")),
                        toLong(metadata(document, "chunk_id")),
                        metadata(document, "chunk_index"),
                        metadata(document, "document_title"),
                        metadata(document, "source")
                ))
                .distinct()
                .toList();
    }

    private String metadata(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    private Long toLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return Long.valueOf(value);
    }

    private KnowledgeQueryLog saveQueryLog(String question, String answer, int topK, double minScore,
                                           String status, String errorMessage, long start) {
        KnowledgeQueryLog queryLog = new KnowledgeQueryLog();
        queryLog.setQuestion(question);
        queryLog.setAnswer(answer);
        queryLog.setTopK(topK);
        queryLog.setMinScore(BigDecimal.valueOf(minScore));
        queryLog.setStatus(status);
        queryLog.setErrorMessage(errorMessage != null ? truncate(errorMessage) : null);
        queryLog.setDurationMs(System.currentTimeMillis() - start);
        queryLog.setCreatedAt(LocalDateTime.now());
        queryLogService.save(queryLog);
        return queryLog;
    }

    private void saveQueryReferences(Long queryLogId, List<Document> hits) {
        if (queryLogId == null || hits == null || hits.isEmpty()) {
            return;
        }
        List<KnowledgeQueryReference> references = hits.stream()
                .map(hit -> {
                    KnowledgeQueryReference reference = new KnowledgeQueryReference();
                    reference.setQueryLogId(queryLogId);
                    reference.setDocumentId(toLong(metadata(hit, "document_id")));
                    reference.setVersionId(toLong(metadata(hit, "version_id")));
                    reference.setChunkId(toLong(metadata(hit, "chunk_id")));
                    reference.setChunkIndex(Integer.valueOf(metadata(hit, "chunk_index")));
                    reference.setSource(metadata(hit, "source"));
                    reference.setCreatedAt(LocalDateTime.now());
                    return reference;
                })
                .toList();
        queryReferenceService.saveBatch(references);
    }

    /**
     * 过滤已禁用的切片。
     */
    private List<Document> filterEnabledChunks(List<Document> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<Long> chunkIds = hits.stream()
                .map(hit -> toLong(metadata(hit, "chunk_id")))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        List<Long> enabledChunkIds = chunkService.lambdaQuery()
                .select(KnowledgeChunk::getId)
                .in(KnowledgeChunk::getId, chunkIds)
                .eq(KnowledgeChunk::getEnabled, 1)
                .eq(KnowledgeChunk::getDelFlag, 0)
                .list()
                .stream()
                .map(KnowledgeChunk::getId)
                .toList();
        return hits.stream()
                .filter(hit -> enabledChunkIds.contains(toLong(metadata(hit, "chunk_id"))))
                .toList();
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "SSE 发送失败: " + e.getMessage(), e);
        }
    }

    private String normalizeQuestion(KnowledgeDocumentQueryRequest request) {
        return request.question() == null ? "" : request.question().trim();
    }

    private int normalizeTopK(KnowledgeDocumentQueryRequest request) {
        return request.topK() == null ? DEFAULT_TOP_K : request.topK();
    }

    private double normalizeMinScore(KnowledgeDocumentQueryRequest request) {
        return request.minScore() == null ? DEFAULT_MIN_SCORE : request.minScore();
    }
}