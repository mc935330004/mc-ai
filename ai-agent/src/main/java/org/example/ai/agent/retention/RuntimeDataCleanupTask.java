package org.example.ai.agent.retention;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * 定时分批清理超过保留期的运行数据。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.data-retention",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class RuntimeDataCleanupTask {

    private final RuntimeDataCleanupRepository repository;
    private final RuntimeDataRetentionProperties properties;

    /**
     * 每日凌晨三点执行清理，避免与白天业务高峰重叠。
     */
    @Scheduled(cron = "${app.data-retention.cron:0 0 3 * * *}")
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        try {
            cleanupExpiredArtifacts();
            cleanupKnowledgeQueries(
                    now.minusDays(properties.getKnowledgeQueryDays())
            );
            cleanupRuntimeRecords(
                    now.minusDays(properties.getRuntimeDays())
            );
            cleanupDeletedChats(
                    now.minusDays(properties.getRuntimeDays())
            );
            cleanupModelUsage(
                    now.minusDays(properties.getModelUsageDays())
            );
            cleanupAuditRecords(
                    now.minusDays(properties.getAuditDays())
            );
        } catch (RuntimeException exception) {
            // 单次清理失败不能停止后续调度，详细异常保留在服务端日志。
            log.error("运行数据定时清理失败", exception);
        }
    }

    /**
     * 先删除快照分块，再删除主记录。
     */
    private void cleanupExpiredArtifacts() {
        deleteParentChildBatches(
                "ai_result_artifact",
                () -> repository.deleteExpiredArtifactChunks(
                        properties.getBatchSize()
                ),
                () -> repository.deleteExpiredArtifacts(
                        properties.getBatchSize()
                )
        );
    }

    /**
     * 先删除知识问答引用，再删除日志主记录。
     */
    private void cleanupKnowledgeQueries(LocalDateTime cutoff) {
        deleteParentChildBatches(
                "knowledge_query_log",
                () -> repository.deleteKnowledgeQueryReferences(
                        cutoff,
                        properties.getBatchSize()
                ),
                () -> repository.deleteKnowledgeQueryLogs(
                        cutoff,
                        properties.getBatchSize()
                )
        );
    }

    /**
     * 清理普通运行记录，定义与配置数据不参与清理。
     */
    private void cleanupRuntimeRecords(LocalDateTime cutoff) {
        deleteParentChildBatches(
                "ai_workflow_run",
                () -> repository.deleteWorkflowRunItems(
                        cutoff,
                        properties.getBatchSize()
                ),
                () -> repository.deleteRuntimeData(
                        "ai_workflow_run",
                        "created_at",
                        cutoff,
                        properties.getBatchSize()
                )
        );
        deleteParentChildBatches(
                "ai_run_trace",
                () -> deleteRunTraceChildren(cutoff),
                () -> repository.deleteRuntimeData(
                        "ai_run_trace",
                        "created_at",
                        cutoff,
                        properties.getBatchSize()
                )
        );
        deleteBatches(
                "ai_pending_action",
                () -> repository.deleteTerminalPendingActions(
                        cutoff,
                        properties.getBatchSize()
                )
        );
    }

    /**
     * 删除Agent运行主记录前，先清理所有以runId关联的运行明细。
     */
    private int deleteRunTraceChildren(LocalDateTime cutoff) {
        int affected = repository.deleteRunSteps(
                cutoff,
                properties.getBatchSize()
        );
        affected += repository.deleteRouteLogsByRunTrace(
                cutoff,
                properties.getBatchSize()
        );
        return affected;
    }

    /**
     * 清理用户已删除并超过保留期的会话、消息和业务状态。
     */
    private void cleanupDeletedChats(LocalDateTime cutoff) {
        int total = 0;
        for (int index = 0;
             index < properties.getMaxBatchesPerRun();
             index++) {
            repository.deleteDeletedChatMessages(
                    cutoff,
                    properties.getBatchSize()
            );
            repository.deleteDeletedConversationStates(
                    cutoff,
                    properties.getBatchSize()
            );
            int affected = repository.deleteDeletedChatSessions(
                    cutoff,
                    properties.getBatchSize()
            );
            total += affected;
            if (affected < properties.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info(
                    "运行数据清理完成，table=ai_chat_session，count={}",
                    total
            );
        }
    }

    /**
     * 模型使用记录使用独立保留期。
     */
    private void cleanupModelUsage(LocalDateTime cutoff) {
        deleteBatches(
                "ai_model_usage",
                () -> repository.deleteRuntimeData(
                        "ai_model_usage",
                        "created_at",
                        cutoff,
                        properties.getBatchSize()
                )
        );
    }

    /**
     * 审计记录使用最长保留期。
     */
    private void cleanupAuditRecords(LocalDateTime cutoff) {
        List<TableRetention> tables = List.of(
                new TableRetention("ai_action_audit_log", "created_at"),
                new TableRetention("ai_model_config_audit_log", "created_at")
        );
        tables.forEach(table -> deleteBatches(
                table.table(),
                () -> repository.deleteRuntimeData(
                        table.table(),
                        table.timeColumn(),
                        cutoff,
                        properties.getBatchSize()
                )
        ));
        deleteBatches(
                "ai_alert_record",
                () -> repository.deleteResolvedAlerts(
                        cutoff,
                        properties.getBatchSize()
                )
        );
    }

    /**
     * 限制单次调度删除批次，避免形成长事务和持续占用数据库。
     */
    private void deleteBatches(
            String table,
            IntSupplier deleteAction) {
        int total = 0;
        for (int index = 0;
             index < properties.getMaxBatchesPerRun();
             index++) {
            int affected = deleteAction.getAsInt();
            total += affected;
            if (affected < properties.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("运行数据清理完成，table={}，count={}", table, total);
        }
    }

    /**
     * 每一批先清理子记录，再立即删除同一批父记录。
     */
    private void deleteParentChildBatches(
            String table,
            IntSupplier deleteChildren,
            IntSupplier deleteParents) {
        int total = 0;
        for (int index = 0;
             index < properties.getMaxBatchesPerRun();
             index++) {
            deleteChildren.getAsInt();
            int affected = deleteParents.getAsInt();
            total += affected;
            if (affected < properties.getBatchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("运行数据清理完成，table={}，count={}", table, total);
        }
    }

    /**
     * 描述允许按统一保留策略清理的审计表。
     */
    private record TableRetention(
            String table,
            String timeColumn) {
    }

}
