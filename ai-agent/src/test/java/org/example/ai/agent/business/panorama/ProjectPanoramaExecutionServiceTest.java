package org.example.ai.agent.business.panorama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaModule;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaProfile;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaModuleMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaProfileMapper;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectPanoramaExecutionServiceTest {

    @Test
    void projectTypeProfileWinsAndModulesAreSorted() {
        ProjectPanoramaProfileMapper profileMapper = mock(ProjectPanoramaProfileMapper.class);
        ProjectPanoramaModuleMapper moduleMapper = mock(ProjectPanoramaModuleMapper.class);
        ProjectPanoramaProfile exact = profile(11L, "工程项目");
        when(profileMapper.selectOne(any())).thenReturn(exact);
        when(moduleMapper.selectList(any())).thenReturn(List.of(
                module(2L, "CASH_FLOW", false, 20),
                module(1L, "CONTRACT", true, 10)
        ));

        ProjectPanoramaPlan plan = new ProjectPanoramaProfileService(
                profileMapper,
                moduleMapper
        ).resolve("工程项目");

        assertThat(plan.profileId()).isEqualTo(11L);
        assertThat(plan.projectType()).isEqualTo("工程项目");
        assertThat(plan.modules()).extracting(ProjectPanoramaPlan.Module::datasetCode)
                .containsExactly("CONTRACT", "CASH_FLOW");
    }

    @Test
    void fallsBackToDefaultProfileWhenProjectTypeHasNoProfile() {
        ProjectPanoramaProfileMapper profileMapper = mock(ProjectPanoramaProfileMapper.class);
        ProjectPanoramaModuleMapper moduleMapper = mock(ProjectPanoramaModuleMapper.class);
        when(profileMapper.selectOne(any())).thenReturn(null, profile(12L, "DEFAULT"));
        ProjectPanoramaModule fallbackModule = module(1L, "BASE", true, 10);
        fallbackModule.setProfileId(12L);
        when(moduleMapper.selectList(any())).thenReturn(List.of(fallbackModule));

        ProjectPanoramaPlan plan = new ProjectPanoramaProfileService(
                profileMapper,
                moduleMapper
        ).resolve("科研项目");

        assertThat(plan.profileId()).isEqualTo(12L);
        assertThat(plan.projectType()).isEqualTo("DEFAULT");
    }

    @Test
    void invalidModuleOrderProducesControlledConfigurationError() {
        ProjectPanoramaProfileMapper profileMapper = mock(ProjectPanoramaProfileMapper.class);
        ProjectPanoramaModuleMapper moduleMapper = mock(ProjectPanoramaModuleMapper.class);
        when(profileMapper.selectOne(any())).thenReturn(profile(11L, "工程项目"));
        ProjectPanoramaModule invalid = module(1L, "CONTRACT", true, 10);
        invalid.setDisplayOrder(null);
        when(moduleMapper.selectList(any())).thenReturn(List.of(
                invalid,
                module(2L, "BUDGET", true, 20)
        ));
        ProjectPanoramaProfileService service = new ProjectPanoramaProfileService(
                profileMapper,
                moduleMapper
        );

        assertThatThrownBy(() -> service.resolve("工程项目"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("项目全景模块配置不合法");
    }

    @Test
    void executesOrderedModulesWithoutInjectingProjectYearAndReportsPartialResult() {
        ProjectPanoramaProfileService profileService = mock(ProjectPanoramaProfileService.class);
        ProjectSubjectAuthorizationService authorizationService = authorizationService();
        PanoramaDatasetExecutor datasetExecutor = mock(PanoramaDatasetExecutor.class);
        BusinessSnapshotService snapshotService = mock(BusinessSnapshotService.class);
        ProjectPanoramaPlan plan = new ProjectPanoramaPlan(
                11L,
                "工程项目",
                "工程项目全景",
                "a".repeat(64),
                List.of(
                        new ProjectPanoramaPlan.Module("CONTRACT", true, 10, 30_000),
                        new ProjectPanoramaPlan.Module("CASH_FLOW", false, 20, 30_000)
                )
        );
        when(profileService.resolve("工程项目")).thenReturn(plan);
        when(datasetExecutor.execute(any(), anyInt()))
                .thenReturn(outcome("CONTRACT", DatasetExecutionStatus.SUCCESS))
                .thenReturn(new PanoramaDatasetExecutor.Result(
                        DatasetExecutionStatus.TIMEOUT,
                        null
                ));
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId("snapshot-contract");
        when(snapshotService.create(any())).thenReturn(snapshot);
        List<String> progress = new ArrayList<>();
        ProjectPanoramaExecutionService service = executionService(
                authorizationService,
                profileService,
                datasetExecutor,
                snapshotService
        );

        ProjectPanoramaResult result = service.execute(
                command(Map.of(
                        "projectYear", 2025,
                        "startDate", "2025-01-01"
                )),
                event -> progress.add(event.datasetCode() + ":" + event.status())
        );

        ArgumentCaptor<DatasetExecutionRequest> requests =
                ArgumentCaptor.forClass(DatasetExecutionRequest.class);
        verify(datasetExecutor, org.mockito.Mockito.times(2))
                .execute(requests.capture(), anyInt());
        assertThat(requests.getAllValues()).extracting(DatasetExecutionRequest::datasetCode)
                .containsExactly("CONTRACT", "CASH_FLOW");
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.subjectType()).isEqualTo(BusinessSubjectType.PROJECT);
            assertThat(request.subjectId()).isEqualTo("project-id-1");
            assertThat(request.canonicalInput())
                    .containsEntry("projectCode", "XXXT2674040")
                    .containsEntry("startDate", "2025-01-01")
                    .doesNotContainKey("projectYear");
        });
        assertThat(result.state()).isEqualTo(PanoramaExecutionState.PARTIAL_SUCCESS);
        assertThat(result.requiredComplete()).isTrue();
        assertThat(result.allModulesComplete()).isFalse();
        assertThat(result.aggregateSnapshotId()).isEqualTo("aggregate-1");
        assertThat(result.modules()).extracting(ProjectPanoramaResult.ModuleResult::snapshotId)
                .containsExactly("snapshot-contract", null);
        assertThat(progress).containsExactly(
                "CONTRACT:RUNNING", "CONTRACT:SUCCESS",
                "CASH_FLOW:RUNNING", "CASH_FLOW:TIMEOUT"
        );

        ArgumentCaptor<BusinessSnapshotService.CreateCommand> snapshots =
                ArgumentCaptor.forClass(BusinessSnapshotService.CreateCommand.class);
        verify(snapshotService).create(snapshots.capture());
        assertThat(snapshots.getValue().items()).singleElement().satisfies(item ->
                assertThat(item.associationType()).isEqualTo(AssociationType.DIRECT)
        );
    }

    @Test
    void requiredFailureMakesRequiredFactsIncomplete() {
        ProjectPanoramaProfileService profileService = mock(ProjectPanoramaProfileService.class);
        ProjectSubjectAuthorizationService authorizationService = authorizationService();
        PanoramaDatasetExecutor datasetExecutor = mock(PanoramaDatasetExecutor.class);
        BusinessSnapshotService snapshotService = mock(BusinessSnapshotService.class);
        when(profileService.resolve("工程项目")).thenReturn(new ProjectPanoramaPlan(
                11L,
                "工程项目",
                "工程项目全景",
                "a".repeat(64),
                List.of(new ProjectPanoramaPlan.Module("CONTRACT", true, 10, 30_000))
        ));
        when(datasetExecutor.execute(any(), anyInt()))
                .thenReturn(outcome("CONTRACT", DatasetExecutionStatus.DENIED));
        ProjectPanoramaExecutionService service = executionService(
                authorizationService,
                profileService,
                datasetExecutor,
                snapshotService
        );

        ProjectPanoramaResult result = service.execute(command(Map.of()), ignored -> { });

        assertThat(result.state()).isEqualTo(PanoramaExecutionState.FAILED);
        assertThat(result.requiredComplete()).isFalse();
        assertThat(result.missingRequiredDatasetCodes()).containsExactly("CONTRACT");
        verify(snapshotService, never()).create(any());
    }

    @Test
    void unexpectedModuleFailureDoesNotPreventLaterModules() {
        ProjectPanoramaProfileService profileService = mock(ProjectPanoramaProfileService.class);
        ProjectSubjectAuthorizationService authorizationService = authorizationService();
        PanoramaDatasetExecutor datasetExecutor = mock(PanoramaDatasetExecutor.class);
        BusinessSnapshotService snapshotService = mock(BusinessSnapshotService.class);
        when(profileService.resolve("工程项目")).thenReturn(new ProjectPanoramaPlan(
                11L,
                "工程项目",
                "工程项目全景",
                "a".repeat(64),
                List.of(
                        new ProjectPanoramaPlan.Module("CONTRACT", true, 10, 30_000),
                        new ProjectPanoramaPlan.Module("OUTPUT", false, 20, 30_000)
                )
        ));
        when(datasetExecutor.execute(any(), anyInt()))
                .thenThrow(new IllegalStateException("downstream unavailable"))
                .thenReturn(outcome("OUTPUT", DatasetExecutionStatus.SUCCESS));
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId("snapshot-output");
        when(snapshotService.create(any())).thenReturn(snapshot);
        ProjectPanoramaExecutionService service = executionService(
                authorizationService,
                profileService,
                datasetExecutor,
                snapshotService
        );

        ProjectPanoramaResult result = service.execute(command(Map.of()), ignored -> { });

        verify(datasetExecutor, org.mockito.Mockito.times(2)).execute(any(), anyInt());
        assertThat(result.state()).isEqualTo(PanoramaExecutionState.PARTIAL_SUCCESS);
        assertThat(result.requiredComplete()).isFalse();
        assertThat(result.modules()).extracting(ProjectPanoramaResult.ModuleResult::status)
                .containsExactly(DatasetExecutionStatus.FAILED, DatasetExecutionStatus.SUCCESS);
    }

    @Test
    void aggregateResultDoesNotSerializeInternalExecutionProvenanceOrCalculationFacts()
            throws Exception {
        DatasetExecutionResult internalResult = result(
                "CONTRACT",
                DatasetExecutionStatus.SUCCESS
        );
        ProjectPanoramaResult result = new ProjectPanoramaResult(
                11L,
                "a".repeat(64),
                "aggregate-1",
                PanoramaExecutionState.COMPLETE,
                true,
                true,
                List.of(),
                List.of(),
                List.of(new ProjectPanoramaResult.ModuleResult(
                        "CONTRACT",
                        true,
                        DatasetExecutionStatus.SUCCESS,
                        true,
                        "snapshot-1",
                        internalResult
                ))
        );

        String json = new ObjectMapper().writeValueAsString(result);

        assertThat(json)
                .doesNotContain("executionResult", "project-id-1", "user-1", "calculation");
    }

    @Test
    void refusesToFanOutBeforeOneProjectIsResolved() {
        ProjectPanoramaProfileService profileService = mock(ProjectPanoramaProfileService.class);
        ProjectSubjectAuthorizationService authorizationService = mock(
                ProjectSubjectAuthorizationService.class
        );
        PanoramaDatasetExecutor datasetExecutor = mock(PanoramaDatasetExecutor.class);
        BusinessSnapshotService snapshotService = mock(BusinessSnapshotService.class);
        ProjectPanoramaExecutionService service = executionService(
                authorizationService,
                profileService,
                datasetExecutor,
                snapshotService
        );
        ProjectPanoramaCommand unresolved = new ProjectPanoramaCommand(
                "run-1", "user-1", "session-1", "Bearer token", Map.of(),
                " ", Map.of()
        );

        assertThatThrownBy(() -> service.execute(unresolved, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        verify(datasetExecutor, never()).execute(any(), anyInt());
    }

    @Test
    void rejectsOverlongSessionBeforeAuthorizationOrModuleCalls() {
        ProjectPanoramaProfileService profileService = mock(ProjectPanoramaProfileService.class);
        ProjectSubjectAuthorizationService authorizationService = mock(
                ProjectSubjectAuthorizationService.class
        );
        PanoramaDatasetExecutor datasetExecutor = mock(PanoramaDatasetExecutor.class);
        BusinessSnapshotService snapshotService = mock(BusinessSnapshotService.class);
        ProjectPanoramaExecutionService service = executionService(
                authorizationService,
                profileService,
                datasetExecutor,
                snapshotService
        );
        ProjectPanoramaCommand invalid = new ProjectPanoramaCommand(
                "run-1", "user-1", "s".repeat(65), "Bearer token", Map.of(),
                "selection-token", Map.of()
        );

        assertThatThrownBy(() -> service.execute(invalid, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sessionId不合法");
        verify(authorizationService, never()).authorize(any());
        verify(datasetExecutor, never()).execute(any(), anyInt());
    }

    private ProjectPanoramaCommand command(Map<String, Object> query) {
        return new ProjectPanoramaCommand(
                "run-1",
                "user-1",
                "session-1",
                "Bearer token",
                Map.of("tenant", "t1"),
                "selection-token",
                query
        );
    }

    private ProjectPanoramaExecutionService executionService(
            ProjectSubjectAuthorizationService authorizationService,
            ProjectPanoramaProfileService profileService,
            PanoramaDatasetExecutor datasetExecutor,
            BusinessSnapshotService snapshotService) {
        ProjectIssueRuleService issueRuleService = mock(ProjectIssueRuleService.class);
        ProjectPanoramaSnapshotService panoramaSnapshotService = mock(
                ProjectPanoramaSnapshotService.class
        );
        ProjectIssueRuleService.RuleSet ruleSet = new ProjectIssueRuleService.RuleSet(
                11L,
                List.of()
        );
        when(issueRuleService.loadRules(anyLong())).thenReturn(ruleSet);
        when(issueRuleService.evaluate(any(ProjectIssueRuleService.RuleSet.class), anyList()))
                .thenReturn(List.of());
        when(panoramaSnapshotService.create(any())).thenReturn(
                new ProjectPanoramaSnapshotService.SnapshotReference(
                        "aggregate-1",
                        "COMPLETE",
                        java.time.LocalDateTime.of(2026, 9, 3, 2, 0)
                )
        );
        return new ProjectPanoramaExecutionService(
                authorizationService,
                profileService,
                datasetExecutor,
                snapshotService,
                issueRuleService,
                panoramaSnapshotService,
                32,
                600_000
        );
    }

    private ProjectSubjectAuthorizationService authorizationService() {
        ProjectSubjectAuthorizationService service = mock(
                ProjectSubjectAuthorizationService.class
        );
        when(service.authorize(any())).thenReturn(new AuthorizedProjectSubject(
                "project-id-1",
                "XXXT2674040",
                "工程项目",
                "一号项目"
        ));
        return service;
    }

    private PanoramaDatasetExecutor.Result outcome(
            String datasetCode,
            DatasetExecutionStatus status) {
        return new PanoramaDatasetExecutor.Result(status, result(datasetCode, status));
    }

    private DatasetExecutionResult result(String datasetCode, DatasetExecutionStatus status) {
        DatasetExecutionSource source = new DatasetExecutionSource(
                "user-1", "session-1", BusinessSubjectType.PROJECT, "project-id-1",
                datasetCode, "1".repeat(64), "wf-" + datasetCode, 1L,
                "2".repeat(64), "3".repeat(64)
        );
        return new DatasetExecutionResult(
                source,
                status,
                status == DatasetExecutionStatus.SUCCESS,
                Map.of(
                        "calculation", Map.of(),
                        "display", Map.of(),
                        "export", Map.of(),
                        "model", Map.of()
                ),
                status == DatasetExecutionStatus.DENIED ? null : "workflow-run-1",
                null,
                null,
                null,
                "proof"
        );
    }

    private ProjectPanoramaProfile profile(Long id, String projectType) {
        ProjectPanoramaProfile profile = new ProjectPanoramaProfile();
        profile.setId(id);
        profile.setProjectType(projectType);
        profile.setProfileName(projectType + "全景");
        profile.setEnabled(true);
        profile.setConfigChecksum("a".repeat(64));
        return profile;
    }

    private ProjectPanoramaModule module(
            Long id,
            String datasetCode,
            boolean required,
            int order) {
        ProjectPanoramaModule module = new ProjectPanoramaModule();
        module.setId(id);
        module.setProfileId(11L);
        module.setDatasetCode(datasetCode);
        module.setRequiredFlag(required);
        module.setDisplayOrder(order);
        module.setTimeoutMs(30_000);
        return module;
    }
}
