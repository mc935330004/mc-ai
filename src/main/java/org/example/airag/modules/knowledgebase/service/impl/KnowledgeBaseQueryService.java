package org.example.airag.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.model.QueryRequest;
import org.example.airag.modules.knowledgebase.model.QueryResponse;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库查询服务
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class KnowledgeBaseQueryService {
    private static final int DEFAULT_TOP_K = 5;
    // 降低相似度阈值，先保证本地联调能召回知识库片段。
    private static final double DEFAULT_MIN_SCORE = 0.2;
    private static final String NO_RESULT_RESPONSE = "抱歉，在选定的知识库中未检索到相关信息。";

    private final ObjectProvider<KnowledgeBaseVectorService> vectorServiceProvider;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final KnowledgeBaseService knowledgeBaseService;


    public QueryResponse queryKnowledgeBase(QueryRequest request) {
        List<Long> knowledgeBaseIds = normalizeKnowledgeBaseIds(request.knowledgeBaseIds());
        String question = normalizeQuestion(request.question());

        if (knowledgeBaseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "至少选择一个知识库");
        }
        if (!StringUtils.hasText(question)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        KnowledgeBaseVectorService vectorService = requireVectorService();
        ChatClient chatClient = requireChatClient();

        // 1. 先从 PGVector 检索与问题最相关的文本块。
        List<Document> relevantDocs = vectorService.similaritySearch(
                question,
                knowledgeBaseIds,
                DEFAULT_TOP_K,
                DEFAULT_MIN_SCORE
        );

        String knowledgeBaseName = buildKnowledgeBaseNames(knowledgeBaseIds);
        Long primaryKnowledgeBaseId = knowledgeBaseIds.get(0);

        if (relevantDocs.isEmpty()) {
            return new QueryResponse(NO_RESULT_RESPONSE, primaryKnowledgeBaseId, knowledgeBaseName);
        }

        // 2. 把检索到的文本块拼成上下文，交给大模型参考。
        String context = buildContext(relevantDocs);

        // 3. 调用大模型，让它严格基于知识库上下文回答。
        String answer = chatClient.prompt()
                .system(buildSystemPrompt())
                .user(buildUserPrompt(context, question))
                .call()
                .content();
        log.info("知识库查询完成: kbIds={}, hitCount={}", knowledgeBaseIds, relevantDocs.size());
        return new QueryResponse(
                normalizeAnswer(answer),
                primaryKnowledgeBaseId,
                knowledgeBaseName,
                buildReferences(relevantDocs)
        );
    }
    /**
     * 查询知识库名称，多个知识库用顿号拼接。
     */
    private String buildKnowledgeBaseNames(List<Long> knowledgeBaseIds) {
        List<KnowledgeBase> knowledgeBases = knowledgeBaseService.listByIds(knowledgeBaseIds);
        if (knowledgeBases == null || knowledgeBases.isEmpty()) {
            return "";
        }

        return knowledgeBases.stream()
                .map(KnowledgeBase::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("、"));
    }

    /**
     * 清理知识库 ID，避免 null 值参与检索。
     */
    private List<Long> normalizeKnowledgeBaseIds(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds == null) {
            return List.of();
        }
        return knowledgeBaseIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 清理问题文本。
     */
    private String normalizeQuestion(String question) {
        return question == null ? "" : question.trim();
    }
    /**
     * 获取向量检索服务。
     */
    private KnowledgeBaseVectorService requireVectorService() {
        KnowledgeBaseVectorService vectorService = vectorServiceProvider.getIfAvailable();
        if (vectorService == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量检索服务未启用，请检查 PGVector 配置");
        }
        return vectorService;
    }
    /**
     * 获取 ChatClient。
     */
    private ChatClient requireChatClient() {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new BusinessException(ErrorCode.AI_SERVICE_UNAVAILABLE, "AI 对话服务未启用，请检查模型配置");
        }
        return builder.build();
    }

    /**
     * 拼接检索出来的文本块。
     */
    private String buildContext(List<Document> documents) {
        return documents.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 构建用户提示词。
     */
    private String buildUserPrompt(String context, String question) {
        return """
                请根据下面的知识库内容回答用户问题。

                【知识库内容】
                %s

                【用户问题】
                %s
                """.formatted(context, question);
    }
    /**
     * 构建系统提示词。
     */
    private String buildSystemPrompt() {
        return """
                你是一个严谨的知识库问答助手。
                你必须优先根据用户提供的知识库内容回答问题。
                如果知识库内容不足以回答问题，请明确说明未检索到足够信息。
                不要编造知识库中没有出现的内容。
                """;
    }

    /**
     * 兜底处理空回答。
     */
    private String normalizeAnswer(String answer) {
        if (!StringUtils.hasText(answer)) {
            return NO_RESULT_RESPONSE;
        }
        return answer.trim();
    }

    /**
     * 构建回答引用来源。
     *
     * <p>source 和 chunk_index 是向量入库时写入 metadata 的字段。</p>
     */
    private List<QueryResponse.Reference> buildReferences(List<Document> documents) {
        return documents.stream()
                .map(document -> new QueryResponse.Reference(
                        getMetadataValue(document, "source"),
                        getMetadataValue(document, "chunk_index")
                ))
                .distinct()
                .toList();
    }

    /**
     * 安全读取 Document metadata，避免空指针。
     */
    private String getMetadataValue(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }
}
