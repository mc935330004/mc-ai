package org.example.ai.agent.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryLog;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryReference;
import org.example.ai.agent.modules.KnowledgeLog.service.KnowledgeQueryLogService;
import org.example.ai.agent.modules.KnowledgeLog.service.KnowledgeQueryReferenceService;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryRequest;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryResponse;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeChunkService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentService;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.springframework.ai.chat.model.ChatResponse;
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
    private final KnowledgeQueryLogService queryLogService;
    private final KnowledgeQueryReferenceService queryReferenceService;
    private final KnowledgeChunkService chunkService;
    private final KnowledgeAccessContext knowledgeAccessContext;
    private final TrackedChatClientService trackedChatClientService;
    /**
     *  只有包含上下文指代词的追问才拼接历史，
     * 避免普通问题被无关会话内容干扰。
     */
    private static final List<String> CONTEXT_REFERENCE_WORDS = List.of(
            "这个", "那个", "这些", "那些", "它",
            "上述", "前面", "刚才", "继续", "该流程", "该制度"
    );

    private static final int MAX_RETRIEVAL_MEMORY_CHARS = 2000;
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

        ModelCallContext context = ModelCallContext.builder()
                .callType(ModelCallType.RAG)
                .callSequence(1)
                .build();
        /*
         * HttpServletRequest不能安全地延迟到异步线程读取，
         * 因此先在请求线程获取可信身份。
         */
        KnowledgeAccessPrincipal principal =knowledgeAccessContext.getRequiredPrincipal();

        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> doStreamQuery(
                        request,
                        emitter,
                        context,
                        principal
                )
        );
        return emitter;
    }

    private void doStreamQuery( KnowledgeDocumentQueryRequest request,
                                SseEmitter emitter,
                                ModelCallContext modelCallContext,
                                KnowledgeAccessPrincipal principal) {
        long start = System.currentTimeMillis();
        String question = normalizeQuestion(request);
        int topK = normalizeTopK(request);
        double minScore = normalizeMinScore(request);
        StringBuilder answerBuilder = new StringBuilder();
        try {
            if (!StringUtils.hasText(question)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
            }
            List<Document> hits = retrieveHits(
                    request,
                    question,
                    topK,
                    minScore,
                    principal
            );
            if (hits.isEmpty()) {
                saveQueryLog(principal,question, NO_RESULT_RESPONSE, topK, minScore, "NO_RESULT", null, start);
                sendEvent(emitter, "message", NO_RESULT_RESPONSE);
                sendEvent(emitter, "references", List.of());
                sendEvent(emitter, "done", "[DONE]");
                emitter.complete();
                return;
            }

            trackedChatClientService.stream(
                            modelCallContext,
                            buildSystemPrompt(),
                            buildUserPrompt(question, buildContext(hits), ""))
                    .map(this::extractStreamContent)
                    .filter(StringUtils::hasText)
                    .doOnNext(content -> {
                        if (StringUtils.hasText(content)) {
                            answerBuilder.append(content);
                            sendEvent(emitter, "message", content);
                        }
                    }).doOnComplete(() -> {
                        String answer = answerBuilder.toString().trim();
                        KnowledgeQueryLog queryLog = saveQueryLog(
                                principal,
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
            saveQueryLog(principal,question, null, topK, minScore, "FAILED", e.getMessage(), start);
            sendEvent(emitter, "error", truncate(e.getMessage()));
            emitter.completeWithError(e);
        }
    }

    /**
     * 企业文档普通问答。
     */
    public KnowledgeDocumentQueryResponse query(KnowledgeDocumentQueryRequest request) {
        ModelCallContext context = ModelCallContext.builder()
                .callType(ModelCallType.RAG)
                .callSequence(1)
                .build();
        return query(request, context);
    }

    /**
     *  兼容普通知识库查询，默认没有会话记忆。
     */
    public KnowledgeDocumentQueryResponse query(
            KnowledgeDocumentQueryRequest request,
            ModelCallContext modelCallContext) {

        return query(request, modelCallContext, "");
    }
    /**
     * Agent 内部 RAG 调用。
     *
     * Agent 编排器可以传入 runId、conversationId、userId，
     * 从而把 RAG Token 汇总到 ai_run_trace。
     */
    public KnowledgeDocumentQueryResponse query(KnowledgeDocumentQueryRequest request,ModelCallContext modelCallContext,
                                                 String conversationMemory) {
        return query(
                request,
                modelCallContext,
                conversationMemory,
                knowledgeAccessContext.getRequiredPrincipal()
        );
    }

    /**
     * 使用请求线程提前捕获的可信身份执行知识库问答。
     *
     * 该入口供Agent异步编排调用，避免在线程池中读取HttpServletRequest。
     */
    public KnowledgeDocumentQueryResponse query(
            KnowledgeDocumentQueryRequest request,
            ModelCallContext modelCallContext,
            String conversationMemory,
            KnowledgeAccessPrincipal principal) {

        validatePrincipal(principal);
        long start = System.currentTimeMillis();
        String question = normalizeQuestion(request);
        int topK = normalizeTopK(request);
        double minScore = normalizeMinScore(request);
        try {
            if (!StringUtils.hasText(question)) {
                throw new BusinessException( ErrorCode.BAD_REQUEST, "问题不能为空" );
            }
            //  上下文追问使用历史信息补全检索语义，向量模型仍使用固定配置。
            String retrievalQuestion = buildRetrievalQuestion(
                    question,
                    conversationMemory
            );
            List<Document> hits = retrieveHits(
                    request,
                    retrievalQuestion,
                    topK,
                    minScore,
                    principal
            );

            if (hits.isEmpty()) {
                saveQueryLog(principal, question, NO_RESULT_RESPONSE, topK,
                        minScore, "NO_RESULT",
                        null, start);
                return new KnowledgeDocumentQueryResponse(NO_RESULT_RESPONSE, List.of());
            }

            ChatResponse response = trackedChatClientService.call(
                    modelCallContext,
                    buildSystemPrompt(),
                    buildUserPrompt(
                            question,
                            buildContext(hits),
                            conversationMemory
                    )
            );

            String answer = response.getResult().getOutput().getText();
            KnowledgeQueryLog queryLog = saveQueryLog(principal,question,
                    answer,
                    topK,
                    minScore,
                    "SUCCESS",
                    null,
                    start );

            saveQueryReferences(queryLog.getId(), hits);

            return new KnowledgeDocumentQueryResponse(
                    StringUtils.hasText(answer)
                            ? answer.trim()
                            : NO_RESULT_RESPONSE,
                    buildReferences(hits) );
        } catch (Exception exception) {
            saveQueryLog(principal,question,
                    null,
                    topK,
                    minScore,
                    "FAILED",
                    exception.getMessage(),
                    start );
            throw exception;
        }
    }
    /**
     *  为包含代词或省略信息的追问补充最近会话，
     * 这里只改变检索文本，不切换或动态配置向量模型。
     */
    private String buildRetrievalQuestion(
            String question,
            String conversationMemory) {

        boolean requiresContext = CONTEXT_REFERENCE_WORDS.stream()
                .anyMatch(question::contains);

        if (!requiresContext || !StringUtils.hasText(conversationMemory)) {
            return question;
        }

        //  限制参与向量检索的历史长度，避免超过 Embedding 输入限制。
        String memory = conversationMemory.length() > MAX_RETRIEVAL_MEMORY_CHARS
                ? conversationMemory.substring(
                conversationMemory.length() - MAX_RETRIEVAL_MEMORY_CHARS
        )
                : conversationMemory;

        return memory + "\n当前追问：" + question;
    }

    /**
     * 在当前用户允许访问的文档版本中执行向量检索。
     */
    private List<Document> retrieveHits(
            KnowledgeDocumentQueryRequest request,
            String question,
            int topK,
            double minScore,
            KnowledgeAccessPrincipal principal) {

        List<KnowledgeDocument> documents =findPublishedDocuments(request, principal);

        List<Long> versionIds = documents.stream()
                .map(KnowledgeDocument::getCurrentVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (versionIds.isEmpty()) {
            return List.of();
        }

        List<Document> hits = requireVectorService()
                .similaritySearchByVersionIds(
                        question,
                        versionIds,
                        topK,
                        minScore
                );

        return filterEnabledChunks(hits);
    }

    /**
     * 查询当前用户有权访问的已发布文档。
     */
    private List<KnowledgeDocument> findPublishedDocuments(
            KnowledgeDocumentQueryRequest request,
            KnowledgeAccessPrincipal principal) {

        LambdaQueryChainWrapper<KnowledgeDocument> query =
                documentService.lambdaQuery()
                        .eq(KnowledgeDocument::getTenantId, principal.tenantId()
                        )
                        .eq(KnowledgeDocument::getDelFlag, 0)
                        .eq(KnowledgeDocument::getStatus, "PUBLISHED"
                        )
                        .isNotNull(KnowledgeDocument::getCurrentVersionId
                        )
                        .in(request.categoryIds() != null && !request.categoryIds().isEmpty(),
                                KnowledgeDocument::getCategoryId,request.categoryIds())
                        .in(request.documentIds() != null  && !request.documentIds().isEmpty(),
                                KnowledgeDocument::getId,
                                request.documentIds()
                        );

        /*
         * PUBLIC只代表当前租户公开。
         *
         * 用户有有效部门时，可以额外检索当前部门文档；
         * 没有部门时只能检索PUBLIC文档。
         */
        query.and(scope -> {
            scope.eq(KnowledgeDocument::getAccessScope,"PUBLIC");
            if (principal.deptId() != null && principal.deptId() > 0) {
                scope.or(department -> department
                        .eq(KnowledgeDocument::getAccessScope,
                                "DEPARTMENT" )
                        .eq(KnowledgeDocument::getOwnerDeptId, principal.deptId())
                );
            }
        });
        return query.list();
    }

    private KnowledgeBaseVectorService requireVectorService() {
        KnowledgeBaseVectorService vectorService = vectorServiceProvider.getIfAvailable();
        if (vectorService == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量检索服务未启用");
        }
        return vectorService;
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
                1. 如果内容适合表格展示，请使用 Markdown 表格。
                2. 表格前后必须保留一个空行。
                3. 表格每一行必须独占一行。
                4. 不要把表格压缩成一行。
                5. 不要删除换行符。
                6. 重要内容可以使用 **加粗**。
                7. 不要输出 HTML。
                8. 不要编造知识库中不存在的内容。
                9. 如果检索内容不足，请明确说明“当前知识库中没有找到足够信息”。
                10. 你必须优先根据检索到的企业知识文档回答。
                11. 如果文档内容不足以回答，请明确说明未检索到足够信息。
                12. 不要编造企业知识文档中没有出现的内容。
                
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
                
                用户可见输出规则：
                1. 只展示用户询问的业务结果、统计数据、必要业务结论和有效文件。
                2. 不展示工作流编码、工作流版本、节点ID、能力编码、运行耗时、
                   批处理节点、数组索引、字段路径、内部状态码、异常堆栈和鉴权信息。
                3. 成功、失败和跳过状态只用于计算必要统计，
                   不输出“成功记录（索引0）”“跳过记录（索引1）”等执行过程分组。
                4. 不得向用户输出SKIPPED_NO_ID等内部枚举，也不解释内部节点跳过原因。
                5. 不输出原始JSON、接口参数、接口地址和程序调试信息。
                6. 同一业务记录存在文件名称和文件地址时，只输出：
                   [文件名称](文件地址)
                   禁止在链接文字之外重复展示文件地址。
                7. 不输出模型名称、Token消耗、生成时间、数据来源声明和AI免责声明。
                8. 查询结果不完整时，只说明缺少的业务结果以及用户需要补充的业务条件。
                """;
    }

    /**
     *  构建带最近会话记忆的 RAG 提示词。
     *
     * 向量检索仍然只使用当前 question，
     * conversationMemory 不参与 Embedding 计算。
     */
    private String buildUserPrompt(
            String question,
            String context,
            String conversationMemory) {

        String memory = StringUtils.hasText(conversationMemory)
                ? conversationMemory
                : "无历史会话";

        return """
                # 历史会话
                %s
    
                # 当前用户问题
                %s
    
                # 本次检索到的知识库内容
                %s
    
                回答要求：
                1. 历史会话只用于理解追问和代词。
                2. 回答事实必须来自本次检索到的知识库内容。
                3. 不得把历史回答当作最新知识库事实。
                4. 请使用 Markdown 格式输出。
                """.formatted(memory, question, context);
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

    /**
     * 保存带有租户和提问人归属的知识问答日志。
     */
    private KnowledgeQueryLog saveQueryLog(
            KnowledgeAccessPrincipal principal,
            String question,
            String answer,
            int topK,
            double minScore,
            String status,
            String errorMessage,
            long start) {

        validatePrincipal(principal);

        KnowledgeQueryLog queryLog = new KnowledgeQueryLog();
        queryLog.setTenantId(principal.tenantId());
        queryLog.setUserId(principal.userId());
        queryLog.setQuestion(question);
        queryLog.setAnswer(answer);
        queryLog.setTopK(topK);
        queryLog.setMinScore(BigDecimal.valueOf(minScore));
        queryLog.setStatus(status);
        queryLog.setErrorMessage(errorMessage == null ? null
                        : truncate(errorMessage));
        queryLog.setDurationMs(System.currentTimeMillis() - start);
        queryLog.setCreatedAt(LocalDateTime.now());

        queryLogService.save(queryLog);
        return queryLog;
    }

    /**
     * 统一校验知识库查询使用的可信身份。
     */
    private void validatePrincipal(KnowledgeAccessPrincipal principal) {
        if (principal == null
                || principal.tenantId() == null
                || principal.tenantId() <= 0
                || !StringUtils.hasText(principal.userId())) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED,
                    "知识库查询缺少有效的登录身份"
            );
        }
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

    /**
     * 从流式 ChatResponse 中安全读取增量文本。
     */
    private String extractStreamContent(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            return "";
        }

        return response.getResult()
                .getOutput()
                .getText();
    }
}
