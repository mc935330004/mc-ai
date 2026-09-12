package org.example.ai.agent.business.snapshot;

import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessSnapshotCleanupJobTest {

    @Test
    void shouldDeleteOnlyUnreferencedItemsBeforeSnapshots() {
        BusinessSnapshotMapper snapshotMapper = mock(BusinessSnapshotMapper.class);
        BusinessSnapshotItemMapper itemMapper = mock(BusinessSnapshotItemMapper.class);
        when(snapshotMapper.selectExpiredUnreferencedIdsForUpdate(100))
                .thenReturn(List.of("snapshot-1"));
        when(itemMapper.deleteBySnapshotId("snapshot-1")).thenReturn(2);
        when(snapshotMapper.deleteExpiredUnreferencedById("snapshot-1")).thenReturn(1);
        BusinessSnapshotCleanupJob job = new BusinessSnapshotCleanupJob(
                snapshotMapper,
                itemMapper,
                100
        );

        BusinessSnapshotCleanupJob.CleanupResult result = job.cleanupBatch();

        assertThat(result.deletedItems()).isEqualTo(2);
        assertThat(result.deletedSnapshots()).isEqualTo(1);
        InOrder order = inOrder(snapshotMapper, itemMapper);
        order.verify(snapshotMapper).selectExpiredUnreferencedIdsForUpdate(100);
        order.verify(itemMapper).deleteBySnapshotId("snapshot-1");
        order.verify(snapshotMapper).deleteExpiredUnreferencedById("snapshot-1");
    }

    @Test
    void cleanupSqlLivesInMapperXmlAndProtectsEveryActiveReference() throws Exception {
        String xml = resource("mapper/BusinessSnapshotMapper.xml");

        assertThat(xml)
                .contains("ai_business_snapshot child")
                .contains("ai_composite_report_section section")
                .contains("ai_project_panorama_snapshot_module panorama_module")
                .contains("ai_project_panorama_snapshot panorama")
                .contains("panorama.expires_at &gt; CURRENT_TIMESTAMP")
                .contains("ai_result_artifact artifact")
                .contains("artifact.expires_at &gt; CURRENT_TIMESTAMP")
                .contains("LIMIT #{batchSize}")
                .contains("FOR UPDATE")
                .contains("deleteExpiredUnreferencedById");
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
