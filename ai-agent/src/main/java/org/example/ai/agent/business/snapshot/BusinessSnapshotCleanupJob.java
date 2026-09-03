package org.example.ai.agent.business.snapshot;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 安全清理过期业务快照。
 *
 * 先删除已证明没有子快照、报告章节和有效结果制品引用的执行项，最后删除主记录。
 */
@Component
@ConditionalOnProperty(
        prefix = "ai.business.snapshot.cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class BusinessSnapshotCleanupJob {

    private final JdbcTemplate jdbcTemplate;
    private final int batchSize;

    @Autowired
    public BusinessSnapshotCleanupJob(
            JdbcTemplate jdbcTemplate,
            @Value("${ai.business.snapshot.cleanup.batch-size:200}") int batchSize) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate不能为空");
        if (batchSize <= 0 || batchSize > 5000) {
            throw new IllegalArgumentException("快照清理批次必须在1至5000之间");
        }
        this.batchSize = batchSize;
    }

    /**
     * 单批事务清理。SQL只记录数量，不读取或输出快照载荷。
     */
    @Scheduled(cron = "${ai.business.snapshot.cleanup.cron:0 20 3 * * *}")
    @Transactional(rollbackFor = Exception.class)
    public CleanupResult cleanupBatch() {
        List<String> candidates = jdbcTemplate.queryForList(
                """
                SELECT snapshot.snapshot_id
                FROM ai_business_snapshot snapshot
                WHERE snapshot.expires_at <= CURRENT_TIMESTAMP
                  AND NOT EXISTS (
                      SELECT 1
                      FROM ai_business_snapshot child
                      WHERE child.source_snapshot_id = snapshot.snapshot_id
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM ai_composite_report_section section
                      WHERE section.snapshot_id = snapshot.snapshot_id
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM ai_business_snapshot_item artifact_item
                      JOIN ai_result_artifact artifact
                        ON artifact.id = artifact_item.result_artifact_id
                      WHERE artifact_item.snapshot_id = snapshot.snapshot_id
                        AND artifact.expires_at > CURRENT_TIMESTAMP
                  )
                ORDER BY snapshot.expires_at
                LIMIT ?
                FOR UPDATE
                """,
                String.class,
                batchSize
        );
        int deletedItems = 0;
        int deletedSnapshots = 0;
        for (String snapshotId : candidates) {
            /*
             * 候选行在本事务中已被FOR UPDATE锁定。派生快照和报告章节创建方必须先锁同一
             * 快照行再写引用；V13没有外键，若绕过此约定就无法防止检查与写入之间的竞态。
             */
            deletedItems += jdbcTemplate.update(
                    "DELETE FROM ai_business_snapshot_item WHERE snapshot_id = ?",
                    snapshotId
            );
            deletedSnapshots += jdbcTemplate.update(
                    """
                    DELETE FROM ai_business_snapshot
                    WHERE snapshot_id = ?
                      AND expires_at <= CURRENT_TIMESTAMP
                      AND NOT EXISTS (
                          SELECT 1
                          FROM ai_business_snapshot_item item
                          WHERE item.snapshot_id = ?
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM ai_business_snapshot child
                          WHERE child.source_snapshot_id = ?
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM ai_composite_report_section section
                          WHERE section.snapshot_id = ?
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM ai_business_snapshot_item artifact_item
                          JOIN ai_result_artifact artifact
                            ON artifact.id = artifact_item.result_artifact_id
                          WHERE artifact_item.snapshot_id = ?
                            AND artifact.expires_at > CURRENT_TIMESTAMP
                      )
                    """,
                    snapshotId,
                    snapshotId,
                    snapshotId,
                    snapshotId,
                    snapshotId
            );
        }
        return new CleanupResult(deletedItems, deletedSnapshots);
    }

    public record CleanupResult(
            int deletedItems,
            int deletedSnapshots) {
    }
}
