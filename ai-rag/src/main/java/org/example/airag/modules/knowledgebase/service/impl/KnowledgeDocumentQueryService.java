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
                    .user(buildUserPrompt(question,buildContext(hits)))
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
                    .user(buildUserPrompt(question,buildContext(hits)))
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
                你是一个企业知识库 AI 助手。
                #回答要求：
                1. 标题格式必须是：### 标题名称，### 后面必须有一个空格。
                2. 如果内容适合表格展示，请使用 Markdown 表格。
                3. 表格前后必须保留一个空行。
                4. 表格每一行必须独占一行。
                5. 不要把表格压缩成一行。
                6. 不要删除换行符。
                7. 重要内容可以使用 **加粗**。
                8. 不要输出 HTML。
                9. 不要编造知识库中不存在的内容。
                10. 如果检索内容不足，请明确说明“当前知识库中没有找到足够信息”。
                11. 你必须优先根据检索到的企业知识文档回答。
                12. 如果文档内容不足以回答，请明确说明未检索到足够信息。
                13. 不要编造企业知识文档中没有出现的内容。
                
                # Java 代码格式要求
                
                如果回答中包含 Java 代码，请必须使用 Markdown 代码块格式：
                
                ```java
                // Java 代码写在这里
                ```
                
                要求：
                1. Java 代码块必须以 ```java 开头。
                2. Java 代码块必须以 ``` 结束。
                3. 代码缩进必须保留。
                4. 不要把代码压缩成一行。
                5. 不要删除代码中的换行符。
                6. 如果是 Spring Boot 示例代码，也必须放在 ```java 代码块中。
                7. 如果是 Maven 依赖，请使用 ```xml 代码块。
                8. 如果是 SQL，请使用 ```sql 代码块。
                """;
    }

    private String buildUserPrompt(String question, String context) {
        return """
                # 用户问题：
                %s

                # 知识库内容
                %s
                请基于以上资料回答用户问题，并使用 Markdown 格式输出。
                """.formatted(question, context);
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