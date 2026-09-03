package org.example.ai.agent.business.panorama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotModuleMapper;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.IssueMatchStatus;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectPanoramaSnapshotServiceTest {

    @Test
    void persistsOneAggregateWithOrderedModuleSnapshotReferences() {
        ProjectPanoramaSnapshotMapper snapshotMapper = mock(ProjectPanoramaSnapshotMapper.class);
        ProjectPanoramaSnapshotModuleMapper moduleMapper = mock(
                ProjectPanoramaSnapshotModuleMapper.class
        );
        BusinessSnapshotMapper businessSnapshotMapper = mock(BusinessSnapshotMapper.class);
        when(businessSnapshotMapper.selectByIdsForUpdate(any())).thenReturn(List.of(
                moduleSnapshot("snapshot-contract", "CONTRACT"),
                moduleSnapshot("snapshot-budget", "BUDGET")
        ));
        when(snapshotMapper.insert(any(
                org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshot.class
        ))).thenReturn(1);
        when(moduleMapper.insert(any(
                org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule.class
        ))).thenReturn(1);
        ProjectPanoramaSnapshotService service = new ProjectPanoramaSnapshotService(
                snapshotMapper,
                moduleMapper,
                businessSnapshotMapper,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"), ZoneOffset.UTC)
        );
        ProjectPanoramaPlan plan = new ProjectPanoramaPlan(
                11L, "工程项目", "工程项目全景", "a".repeat(64),
                List.of(
                        new ProjectPanoramaPlan.Module("CONTRACT", true, 10, 30_000),
                        new ProjectPanoramaPlan.Module("BUDGET", true, 20, 30_000)
                )
        );
        List<ProjectPanoramaResult.ModuleResult> modules = List.of(
                moduleResult("CONTRACT", "snapshot-contract"),
                moduleResult("BUDGET", "snapshot-budget")
        );

        ProjectPanoramaSnapshotService.SnapshotReference aggregate = service.create(
                new ProjectPanoramaSnapshotService.CreateCommand(
                "user-1",
                "session-1",
                new AuthorizedProjectSubject(
                        "project-id-1", "XXXT2674040", "工程项目", "一号项目"
                ),
                plan,
                Map.of("projectCode", "XXXT2674040"),
                List.of(new ProjectIssueResult(
                        "CONTRACT_OVER_BUDGET",
                        IssueMatchStatus.NOT_MATCHED,
                        "HIGH",
                        "未命中",
                        List.of("contractAmount", "budgetAmount")
                )),
                modules
        ));

        assertThat(aggregate.panoramaSnapshotId()).hasSize(32);
        assertThat(aggregate.expiresAt())
                .isEqualTo(LocalDateTime.of(2026, 9, 3, 2, 0));
        ArgumentCaptor<org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule>
                references = ArgumentCaptor.forClass(
                        org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule.class
                );
        verify(moduleMapper, org.mockito.Mockito.times(2)).insert(references.capture());
        assertThat(references.getAllValues())
                .extracting(org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule::getSnapshotId)
                .containsExactly("snapshot-contract", "snapshot-budget");
    }

    @Test
    void rejectsModuleSnapshotFromDifferentQueryScope() {
        ProjectPanoramaSnapshotMapper snapshotMapper = mock(ProjectPanoramaSnapshotMapper.class);
        ProjectPanoramaSnapshotModuleMapper moduleMapper = mock(
                ProjectPanoramaSnapshotModuleMapper.class
        );
        BusinessSnapshotMapper businessSnapshotMapper = mock(BusinessSnapshotMapper.class);
        BusinessSnapshot snapshot = moduleSnapshot("snapshot-contract", "CONTRACT");
        snapshot.setQueryHash("f".repeat(64));
        when(businessSnapshotMapper.selectByIdsForUpdate(any())).thenReturn(List.of(snapshot));
        ProjectPanoramaSnapshotService service = new ProjectPanoramaSnapshotService(
                snapshotMapper,
                moduleMapper,
                businessSnapshotMapper,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"), ZoneOffset.UTC)
        );
        ProjectPanoramaPlan plan = new ProjectPanoramaPlan(
                11L,
                "工程项目",
                "工程项目全景",
                "a".repeat(64),
                List.of(new ProjectPanoramaPlan.Module("CONTRACT", true, 10, 30_000))
        );

        assertThatThrownBy(() -> service.create(new ProjectPanoramaSnapshotService.CreateCommand(
                "user-1",
                "session-1",
                new AuthorizedProjectSubject(
                        "project-id-1", "XXXT2674040", "工程项目", "一号项目"
                ),
                plan,
                Map.of("projectCode", "XXXT2674040"),
                List.of(),
                List.of(moduleResult("CONTRACT", "snapshot-contract"))
        ))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已失效或不属于当前项目会话");
        verify(snapshotMapper, never()).insert(any(
                org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshot.class
        ));
    }

    private BusinessSnapshot moduleSnapshot(String snapshotId, String datasetCode) {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setUserId("user-1");
        snapshot.setSessionId("session-1");
        snapshot.setSubjectType("PROJECT");
        snapshot.setSubjectId("project-id-1");
        snapshot.setDatasetCode(datasetCode);
        snapshot.setStatus("COMPLETE");
        snapshot.setDataComplete(true);
        snapshot.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(
                        Map.of("projectCode", "XXXT2674040")
                )
        ));
        snapshot.setConfigChecksum("2".repeat(64));
        snapshot.setFieldPolicyChecksum("3".repeat(64));
        snapshot.setExpiresAt(LocalDateTime.of(2026, 9, 3, 2, 0));
        return snapshot;
    }

    private ProjectPanoramaResult.ModuleResult moduleResult(
            String datasetCode,
            String snapshotId) {
        return new ProjectPanoramaResult.ModuleResult(
                datasetCode,
                true,
                DatasetExecutionStatus.SUCCESS,
                true,
                snapshotId,
                executionResult(datasetCode)
        );
    }

    private DatasetExecutionResult executionResult(String datasetCode) {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        "user-1",
                        "session-1",
                        BusinessSubjectType.PROJECT,
                        "project-id-1",
                        datasetCode,
                        "1".repeat(64),
                        "wf-" + datasetCode,
                        1L,
                        "2".repeat(64),
                        "3".repeat(64)
                ),
                DatasetExecutionStatus.SUCCESS,
                true,
                Map.of(
                        "calculation", Map.of(),
                        "display", Map.of(),
                        "export", Map.of(),
                        "model", Map.of()
                ),
                "workflow-run-1",
                null,
                null,
                null,
                "proof"
        );
    }
}
