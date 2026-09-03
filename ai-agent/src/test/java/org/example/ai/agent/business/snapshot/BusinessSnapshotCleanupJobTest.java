package org.example.ai.agent.business.snapshot;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

class BusinessSnapshotCleanupJobTest {

    @Test
    void shouldDeleteOnlyUnreferencedItemsBeforeSnapshots() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(100)))
                .thenReturn(java.util.List.of("snapshot-1"));
        when(jdbcTemplate.update(anyString(), eq("snapshot-1"))).thenReturn(2);
        when(jdbcTemplate.update(
                anyString(), eq("snapshot-1"), eq("snapshot-1"),
                eq("snapshot-1"), eq("snapshot-1"), eq("snapshot-1")
        )).thenReturn(1);
        BusinessSnapshotCleanupJob job = new BusinessSnapshotCleanupJob(jdbcTemplate, 100);

        BusinessSnapshotCleanupJob.CleanupResult result = job.cleanupBatch();

        assertThat(result.deletedItems()).isEqualTo(2);
        assertThat(result.deletedSnapshots()).isEqualTo(1);
        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).queryForList(anyString(), eq(String.class), eq(100));
        order.verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains(
                "DELETE FROM ai_business_snapshot_item"), eq("snapshot-1"));
        order.verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains(
                "DELETE FROM ai_business_snapshot"),
                eq("snapshot-1"), eq("snapshot-1"), eq("snapshot-1"),
                eq("snapshot-1"), eq("snapshot-1"));
    }

    @Test
    void cleanupSqlMustProtectChildrenReportsAndArtifacts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BusinessSnapshotCleanupJob job = new BusinessSnapshotCleanupJob(jdbcTemplate, 10);

        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(10)))
                .thenReturn(java.util.List.of());
        job.cleanupBatch();

        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).queryForList(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("ai_business_snapshot child")
                        && sql.contains("ai_composite_report_section section")
                        && sql.contains("ai_result_artifact artifact")
                        && sql.contains("artifact.expires_at > CURRENT_TIMESTAMP")
                        && sql.contains("LIMIT ?")
                        && sql.contains("FOR UPDATE")
                        && !sql.contains("DELETE snapshot FROM")
        ), eq(String.class), eq(10));
    }

    @Test
    void finalDeleteMustRecheckEveryReferenceAfterItemsAreRemoved() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BusinessSnapshotCleanupJob job = new BusinessSnapshotCleanupJob(jdbcTemplate, 10);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), eq(10)))
                .thenReturn(java.util.List.of("snapshot-1"));

        job.cleanupBatch();

        org.mockito.Mockito.verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.<String>argThat(sql ->
                        sql.contains("ai_business_snapshot child")
                                && sql.contains("ai_composite_report_section section")
                                && sql.contains("ai_result_artifact artifact")
                                && sql.contains("artifact.expires_at > CURRENT_TIMESTAMP")
                ),
                eq("snapshot-1"), eq("snapshot-1"), eq("snapshot-1"),
                eq("snapshot-1"), eq("snapshot-1")
        );
    }
}
