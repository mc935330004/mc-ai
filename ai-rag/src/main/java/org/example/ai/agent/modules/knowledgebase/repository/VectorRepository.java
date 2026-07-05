package org.example.ai.agent.modules.knowledgebase.repository;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PGVector 向量表操作仓库。
 *
 * <p>Spring AI 的 VectorStore 负责写入向量；这里负责补充业务需要的清理和 metadata 提升操作。</p>
 */
@Slf4j
@Repository
@ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "pgvector", matchIfMissing = true)
public class VectorRepository {

    private final JdbcTemplate jdbcTemplate;

    public VectorRepository(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 删除指定知识库已经生效的正式向量。
     *
     * <p>用于重新向量化前清理旧数据，避免同一个知识库命中旧 chunk。</p>
     */
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        String sql = """
                DELETE FROM vector_store
                WHERE metadata->>'kb_id' = ?
                """;
        try {
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString());
            log.info("已删除知识库旧向量: kbId={}, rows={}", knowledgeBaseId, deletedRows);
            return deletedRows;
        } catch (Exception e) {
            log.error("删除知识库旧向量失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "删除旧向量失败");
        }
    }

    /**
     * 删除指定向量化任务写入的临时向量。
     *
     * <p>当分批写入中途失败时，用 jobId 清理已写入的 pending 数据。</p>
     */
    public int deleteByVectorJobId(String jobId) {
        String sql = """
                DELETE FROM vector_store
                WHERE metadata->>'kb_vector_job_id' = ?
                """;
        try {
            int deletedRows = jdbcTemplate.update(sql, jobId);
            log.info("已清理临时向量: jobId={}, rows={}", jobId, deletedRows);
            return deletedRows;
        } catch (Exception e) {
            log.error("清理临时向量失败: jobId={}, error={}", jobId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "清理临时向量失败");
        }
    }

    /**
     * 将临时向量提升为正式知识库向量。
     *
     * <p>写入阶段先把 kb_id 标记为 pending，全部写入成功后再统一改成真实知识库 ID。</p>
     */
    public int promoteVectorJob(Long knowledgeBaseId, String jobId) {
        String sql = """
                UPDATE vector_store
                SET metadata = (jsonb_set(
                        metadata::jsonb,
                        '{kb_id}',
                        to_jsonb(?::text),
                        true
                    ) - 'kb_vector_job_id' - 'kb_target_id')::json
                WHERE metadata->>'kb_vector_job_id' = ?
                """;
        try {
            int updatedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), jobId);
            log.info("临时向量已提升为正式向量: kbId={}, jobId={}, rows={}",
                    knowledgeBaseId, jobId, updatedRows);
            return updatedRows;
        } catch (Exception e) {
            log.error("提升临时向量失败: kbId={}, jobId={}, error={}",
                    knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "提升临时向量失败");
        }
    }

    /**
     * 删除指定文档版本的向量。
     *
     * 注意：
     * 这里依赖向量写入时 metadata 中包含 version_id。
     */
    public void deleteByVersionId(Long versionId) {
        if (versionId == null) {
            return;
        }
        jdbcTemplate.update("""
            DELETE FROM vector_store
            WHERE metadata ->> 'version_id' = ?
            """, versionId.toString());
    }

}