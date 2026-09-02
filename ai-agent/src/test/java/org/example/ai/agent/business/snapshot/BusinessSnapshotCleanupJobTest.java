package org.example.ai.agent.business.snapshot;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessSnapshotCleanupJobTest {

    @Test
    void shouldDeleteOnlyUnreferencedItemsBeforeSnapshots() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), eq(100))).thenReturn(2, 1);
        BusinessSnapshotCleanupJob job = new BusinessSnapshotCleanupJob(jdbcTemplate, 100);

        BusinessSnapshotCleanupJob.CleanupResult result = job.cleanupBatch();

        assertThat(result.deletedItems()).isEqualTo(2);
        assertThat(result.deletedSnapshots()).isEqualTo(1);
        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains(
                "DELETE item"), eq(100));
        order.verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.contains(
                "DELETE snapshot"), eq(100));
    }

    @Test
    void cleanupSqlMustProtectChildrenReportsAndArtifacts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        BusinessSnapshotCleanupJob job = new BusinessSnapshotCleanupJob(jdbcTemplate, 10);

        job.cleanupBatch();

        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).update(org.mockito.ArgumentMatchers.argThat(sql ->
                sql.contains("ai_business_snapshot child")
                        && sql.contains("ai_composite_report_section section")
                        && sql.contains("ai_result_artifact artifact")
                        && sql.contains("artifact.expires_at > CURRENT_TIMESTAMP")
        ), eq(10));
        order.verify(jdbcTemplate).update(anyString(), eq(10));
    }
}
