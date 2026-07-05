package org.example.ai.agent.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modules.knowledgebase.repository.VectorRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库向量化服务。
 *
 * <p>负责文本切块、写入 PGVector，并把临时向量提升为正式可检索向量。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(VectorStore.class)
public class KnowledgeBaseVectorService {

    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private static final String CHUNK_INDEX = "chunk_index";
    private static final String SOURCE = "source";

    private final VectorStore vectorStore;
    private final VectorRepository vectorRepository;
    private final TextSplitter textSplitter = TokenTextSplitter.builder().build();

    /**
     * 将知识库文本切分为多个片段，并写入 PGVector。
     *
     * @param knowledgeBaseId 知识库 ID，用于后续按知识库过滤检索
     * @param content         已解析出的纯文本内容
     * @param sourceName      原始文件名，用于向量 metadata 标记来源
     * @return 实际写入的文本块数量
     */
    public int vectorizeAndStore(Long knowledgeBaseId, String content, String sourceName) {
        if (knowledgeBaseId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库内容不能为空");
        }

        // 将一整篇文档切成多个适合 embedding 的文本块。
        List<Document> chunks = textSplitter.apply(List.of(new Document(content)));
        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "文本切分结果为空");
        }

        String jobId = UUID.randomUUID().toString();
        applyPendingMetadata(chunks, knowledgeBaseId, sourceName, jobId);

        try {
            // 分批写入，避免一次提交太多文本块导致 embedding 接口压力过大。
            for (int start = 0; start < chunks.size(); start += MAX_BATCH_SIZE) {
                int end = Math.min(start + MAX_BATCH_SIZE, chunks.size());
                vectorStore.add(chunks.subList(start, end));
            }

            // 全部分批写入成功后，先删除旧正式向量，再把本次临时向量提升为正式向量。
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);

            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}",
                    knowledgeBaseId, jobId, chunks.size());
            return chunks.size();
        } catch (Exception e) {
            cleanupPendingVectors(jobId);
            log.error("知识库向量化失败: kbId={}, jobId={}, error={}",
                    knowledgeBaseId, jobId, e.getMessage(), e);
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "知识库向量化失败: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * 给每个 chunk 写入临时 metadata。
     *
     * <p>pending kb_id 可以避免“只写入一部分向量”时被正式检索命中。</p>
     */
    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String sourceName, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
            chunk.getMetadata().put(CHUNK_INDEX, String.valueOf(i));
            chunk.getMetadata().put(SOURCE, sourceName);
        }
    }

    /**
     * 尽力清理失败任务产生的临时向量。
     */
    private void cleanupPendingVectors(String jobId) {
        try {
            vectorRepository.deleteByVectorJobId(jobId);
        } catch (Exception cleanupError) {
            log.warn("清理临时向量失败，后续可按 jobId 手动补偿: jobId={}, error={}",
                    jobId, cleanupError.getMessage(), cleanupError);
        }
    }
    /**
     * 基于用户问题，从 PGVector 中检索指定知识库的相关文本块。
     *
     * @param query 用户问题
     * @param knowledgeBaseIds 知识库 ID 列表
     * @param topK 返回最相关的文本块数量
     * @param minScore 最低相似度阈值
     * @return 命中的文档片段
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("开始知识库向量检索: query={}, kbIds={}, topK={}, minScore={}",
                query, knowledgeBaseIds, topK, minScore);
        try {

        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));
        // 设置最低相似度，避免召回弱相关内容。
        if (minScore > 0) {
            builder.similarityThreshold(minScore);
        }
        // 按 kb_id metadata 过滤，只查用户选择的知识库。
        if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
            builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
        }
        List<Document> results = vectorStore.similaritySearch(builder.build());
            return results.stream()
                .limit(topK)
                .collect(Collectors.toList());
        }catch (Exception e){
            log.error("知识库向量检索失败: {}", e.getMessage(), e);
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                    "知识库向量检索失败: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     *  构建知识库过滤条件
     * @param knowledgeBaseIds
     * @return
     */
    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }

    /**
     * 删除指定知识库的所有向量数据。
     *
     * <p>删除知识库时使用，避免 PGVector 中留下无效 chunk。</p>
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
    }

    /**
     * 基于企业文档版本ID检索向量。
     *
     * 新企业文档链路写入向量时，metadata 中包含 version_id。
     * 正式问答只应该传入已发布文档的 currentVersionId，避免草稿、废止版本进入回答。
     */
    public List<Document> similaritySearchByVersionIds(String query,List<Long> versionIds,int topK,
            double minScore) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        if (versionIds == null || versionIds.isEmpty()) {
            return List.of();
        }
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query.trim())
                .topK(Math.max(topK, 1));
        if (minScore > 0) {
            builder.similarityThreshold(minScore);
        }
        // 按 version_id metadata 过滤，只查指定版本。
        builder.filterExpression(buildVersionFilterExpression(versionIds));
        List<Document> results = vectorStore.similaritySearch(builder.build());
        return results.stream()
                        .limit(topK)
                        .toList();
    }

    /**
     * 构建 PGVector metadata 过滤条件。
     *
     * metadata.version_id 在 KnowledgeDocumentVersionServiceImpl.writeVectors() 中写入。
     */
    private String buildVersionFilterExpression(List<Long> versionIds) {
        String values = versionIds.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(id -> "'" + id + "'")
                .collect(Collectors.joining(", "));
        return "version_id in [" + values + "]";
    }
}