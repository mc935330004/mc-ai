package org.example.ai.agent.business.panorama;

import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotModuleMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectPanoramaSnapshotCleanupJobTest {

    @Test
    void deletesExpiredModuleReferencesBeforeAggregate() {
        ProjectPanoramaSnapshotMapper snapshotMapper = mock(
                ProjectPanoramaSnapshotMapper.class
        );
        ProjectPanoramaSnapshotModuleMapper moduleMapper = mock(
                ProjectPanoramaSnapshotModuleMapper.class
        );
        when(snapshotMapper.selectExpiredIdsForUpdate(100)).thenReturn(List.of("aggregate-1"));
        when(moduleMapper.deleteByPanoramaSnapshotId("aggregate-1")).thenReturn(2);
        when(snapshotMapper.deleteExpiredByIdWithoutModules("aggregate-1")).thenReturn(1);
        ProjectPanoramaSnapshotCleanupJob job = new ProjectPanoramaSnapshotCleanupJob(
                snapshotMapper,
                moduleMapper,
                100
        );

        ProjectPanoramaSnapshotCleanupJob.CleanupResult result = job.cleanupBatch();

        assertThat(result.deletedModuleReferences()).isEqualTo(2);
        assertThat(result.deletedSnapshots()).isEqualTo(1);
        InOrder order = inOrder(snapshotMapper, moduleMapper);
        order.verify(snapshotMapper).selectExpiredIdsForUpdate(100);
        order.verify(moduleMapper).deleteByPanoramaSnapshotId("aggregate-1");
        order.verify(snapshotMapper).deleteExpiredByIdWithoutModules("aggregate-1");
    }

    @Test
    void cleanupSqlLivesInCorrespondingMapperXmlFiles() throws Exception {
        String snapshotXml = resource("mapper/ProjectPanoramaSnapshotMapper.xml");
        String moduleXml = resource("mapper/ProjectPanoramaSnapshotModuleMapper.xml");

        assertThat(snapshotXml)
                .contains("selectExpiredIdsForUpdate")
                .contains("LIMIT #{batchSize}")
                .contains("FOR UPDATE")
                .contains("deleteExpiredByIdWithoutModules");
        assertThat(moduleXml)
                .contains("deleteByPanoramaSnapshotId")
                .contains("DELETE FROM ai_project_panorama_snapshot_module");
    }

    private String resource(String path) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
