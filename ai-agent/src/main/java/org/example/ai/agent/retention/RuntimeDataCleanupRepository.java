package org.example.ai.agent.retention;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 运行数据分批清理数据访问层。
 */
@Repository
@RequiredArgsConstructor
public class RuntimeDataCleanupRepository {

    private static final Set<String> ALLOWED_TABLES = Set.of(
                "knowledge_query_log",
                "ai_workflow_run_item",
                "ai_workflow_run",
                "ai_run_step",
                "ai_run_trace",
                "ai_model_usage",
                "ai_action_audit_log",
                "ai_model_config_audit_log"
    );

    private static final Set<String> ALLOWED_TIME_COLUMNS = Set.of(
            "created_at"
    );

    private final JdbcTemplate jdbcTemplate;

    /**
     * 删除已经过期的结果快照分块。
     */
    public int deleteExpiredArtifactChunks(int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE chunk
                FROM ai_result_artifact_chunk chunk
                JOIN (
                    SELECT id
                    FROM ai_result_artifact
                    WHERE expires_at < CURRENT_TIMESTAMP
                    ORDER BY expires_at
                    LIMIT ?
                ) expired ON expired.id = chunk.artifact_id
                """,
                batchSize
        );
    }

    /**
     * 删除已经过期的结果快照主记录。
     */
    public int deleteExpiredArtifacts(int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE FROM ai_result_artifact
                WHERE expires_at < CURRENT_TIMESTAMP
                ORDER BY expires_at
                LIMIT ?
                """,
                batchSize
        );
    }

    /**
     * 删除超过保留期的知识问答引用。
     */
    public int deleteKnowledgeQueryReferences(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE ref
                FROM knowledge_query_reference ref
                JOIN (
                    SELECT id
                    FROM knowledge_query_log
                    WHERE created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                ) expired ON expired.id = ref.query_log_id
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 删除超过保留期的知识问答日志。
     */
    public int deleteKnowledgeQueryLogs(
            LocalDateTime cutoff,
            int batchSize) {
        return deleteBefore(
                "knowledge_query_log",
                "created_at",
                cutoff,
                batchSize
        );
    }

    /**
     * 删除超过保留期的普通运行数据。
     */
    public int deleteRuntimeData(
            String table,
            String timeColumn,
            LocalDateTime cutoff,
            int batchSize) {
        return deleteBefore(
                table,
                timeColumn,
                cutoff,
                batchSize
        );
    }

    /**
     * 删除超过保留期且没有人工反馈的运行路由日志。
     */
    public int deleteRouteLogsByRunTrace(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE route_log
                FROM ai_capability_route_log route_log
                JOIN (
                    SELECT run_id
                    FROM ai_run_trace
                    WHERE created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                ) expired ON expired.run_id = route_log.run_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM ai_capability_route_feedback feedback
                    WHERE feedback.route_log_id = route_log.id
                )
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 删除指定运行主记录对应的工作流明细。
     */
    public int deleteWorkflowRunItems(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE item
                FROM ai_workflow_run_item item
                JOIN (
                    SELECT run_id
                    FROM ai_workflow_run
                    WHERE created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                ) expired ON expired.run_id = item.run_id
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 删除指定Agent运行主记录对应的步骤。
     */
    public int deleteRunSteps(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE step
                FROM ai_run_step step
                JOIN (
                    SELECT run_id
                    FROM ai_run_trace
                    WHERE created_at < ?
                    ORDER BY created_at
                    LIMIT ?
                ) expired ON expired.run_id = step.run_id
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 删除已经逻辑删除且超过保留期会话的消息。
     */
    public int deleteDeletedChatMessages(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE message
                FROM ai_chat_message message
                JOIN (
                    SELECT id
                    FROM ai_chat_session
                    WHERE deleted = 1
                      AND updated_at < ?
                    ORDER BY updated_at
                    LIMIT ?
                ) expired ON expired.id = message.session_id
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 删除已经逻辑删除且超过保留期会话的业务状态。
     */
    public int deleteDeletedConversationStates(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE conversation_state
                FROM ai_conversation_state conversation_state
                JOIN (
                    SELECT id
                    FROM ai_chat_session
                    WHERE deleted = 1
                      AND updated_at < ?
                    ORDER BY updated_at
                    LIMIT ?
                ) expired ON expired.id = conversation_state.session_id
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 物理删除已经逻辑删除且超过保留期的聊天会话。
     */
    public int deleteDeletedChatSessions(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE FROM ai_chat_session
                WHERE deleted = 1
                  AND updated_at < ?
                ORDER BY updated_at
                LIMIT ?
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 删除超过保留期的终态待确认操作。
     */
    public int deleteTerminalPendingActions(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE FROM ai_pending_action
                WHERE status IN (
                    'SUCCESS', 'FAILED', 'CANCELLED', 'EXPIRED'
                )
                  AND updated_at < ?
                ORDER BY updated_at
                LIMIT ?
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 删除超过审计保留期的已解决告警。
     */
    public int deleteResolvedAlerts(
            LocalDateTime cutoff,
            int batchSize) {
        return jdbcTemplate.update(
                """
                DELETE FROM ai_alert_record
                WHERE status = 'RESOLVED'
                  AND resolved_at < ?
                ORDER BY resolved_at
                LIMIT ?
                """,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }

    /**
     * 对固定白名单表执行分批删除。
     */
    private int deleteBefore(
            String table,
            String timeColumn,
            LocalDateTime cutoff,
            int batchSize) {
        if (!ALLOWED_TABLES.contains(table)
                || !ALLOWED_TIME_COLUMNS.contains(timeColumn)) {
            throw new IllegalArgumentException(
                    "运行数据清理表或时间字段不在允许列表中"
            );
        }
        String sql = "DELETE FROM "
                + table
                + " WHERE "
                + timeColumn
                + " < ? ORDER BY "
                + timeColumn
                + " LIMIT ?";
        return jdbcTemplate.update(
                sql,
                Timestamp.valueOf(cutoff),
                batchSize
        );
    }
}
