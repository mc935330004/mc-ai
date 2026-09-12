package org.example.ai.agent.business.panorama;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshot;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotModuleMapper;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.IssueMatchStatus;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectPanoramaSnapshotReuseServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 9, 10, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC
    );
    private static final String PROFILE_CHECKSUM = "a".repeat(64);
    private static final String DATASET_CHECKSUM = "b".repeat(64);
    private static final String FIELD_POLICY_CHECKSUM = "c".repeat(64);

    @Mock
    private ProjectSubjectAuthorizationService authorizationService;
    @Mock
    private ProjectPanoramaProfileService profileService;
    @Mock
    private BusinessSnapshotAccessService accessService;
    @Mock
    private ProjectPanoramaSnapshotMapper snapshotMapper;
    @Mock
    private ProjectPanoramaSnapshotModuleMapper moduleMapper;
    @Mock
    private BusinessSnapshotMapper businessSnapshotMapper;

    private ObjectMapper objectMapper;
    private ProjectPanoramaSnapshotReuseService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ProjectPanoramaSnapshotReuseService(
                authorizationService,
                profileService,
                accessService,
                snapshotMapper,
                moduleMapper,
                businessSnapshotMapper,
                objectMapper,
                CLOCK
        );
    }

    @Test
    void validSnapshotRestoresOrderedSafeChannelsIntegrityAndIssues() throws Exception {
        arrangeValidReuse();

        Optional<ProjectPanoramaResult> reused = service.reuse(command());

        assertThat(reused).isPresent();
        ProjectPanoramaResult result = reused.orElseThrow();
        assertThat(result.profileId()).isEqualTo(11L);
        assertThat(result.profileChecksum()).isEqualTo(PROFILE_CHECKSUM);
        assertThat(result.aggregateSnapshotId()).isEqualTo("panorama-1");
        assertThat(result.state()).isEqualTo(PanoramaExecutionState.COMPLETE);
        assertThat(result.requiredComplete()).isTrue();
        assertThat(result.allModulesComplete()).isTrue();
        assertThat(result.missingRequiredDatasetCodes()).isEmpty();
        assertThat(result.issues()).containsExactly(issue());
        assertThat(result.modules())
                .extracting(ProjectPanoramaResult.ModuleResult::datasetCode)
                .containsExactly("CONTRACT", "BUDGET");
        assertThat(result.modules())
                .extracting(ProjectPanoramaResult.ModuleResult::status)
                .containsExactly(DatasetExecutionStatus.SUCCESS, DatasetExecutionStatus.EMPTY);
        assertThat(result.modules())
                .extracting(ProjectPanoramaResult.ModuleResult::snapshotId)
                .containsExactly("snapshot-contract", "snapshot-budget");
        assertThat(result.modules()).allSatisfy(module -> {
            assertThat(module.dataComplete()).isTrue();
            assertThat(module.executionResult()).isNull();
            assertThat(module.fieldPolicyChecksum()).isEqualTo(FIELD_POLICY_CHECKSUM);
        });
        assertThat(result.modules().get(0).displayFacts())
                .containsOnlyKeys("contractAmount")
                .containsEntry("contractAmount", "100万元");
        assertThat(result.modules().get(0).modelFacts())
                .containsOnlyKeys("contractSummary")
                .containsEntry("contractSummary", "正常");
        assertThat(result.toString()).doesNotContain(
                "raw-calculation-secret", "raw-export-secret", "Bearer secret", "tenant-secret"
        );
    }

    @Test
    void aggregateOwnerSessionOrAuthorizedProjectMismatchRejectsReuse() throws Exception {
        arrangeValidReuse();
        List<Consumer<ProjectPanoramaSnapshot>> mismatches = List.of(
                snapshot -> snapshot.setUserId("other-user"),
                snapshot -> snapshot.setSessionId("other-session"),
                snapshot -> snapshot.setProjectId("other-project"),
                snapshot -> snapshot.setProjectCode("other-code"),
                snapshot -> snapshot.setProjectType("other-type")
        );

        for (Consumer<ProjectPanoramaSnapshot> mismatch : mismatches) {
            ProjectPanoramaSnapshot snapshot = aggregateSnapshot();
            mismatch.accept(snapshot);
            when(snapshotMapper.selectById("panorama-1")).thenReturn(snapshot);

            assertThat(service.reuse(command())).isEmpty();
        }
    }

    @Test
    void expiredAggregateRejectsReuse() throws Exception {
        arrangeValidReuse();
        ProjectPanoramaSnapshot snapshot = aggregateSnapshot();
        snapshot.setExpiresAt(NOW);
        when(snapshotMapper.selectById("panorama-1")).thenReturn(snapshot);

        assertThat(service.reuse(command())).isEmpty();
    }

    @Test
    void canonicalQueryHashMismatchRejectsReuse() throws Exception {
        arrangeValidReuse();
        ProjectPanoramaSnapshot snapshot = aggregateSnapshot();
        snapshot.setQueryHash("f".repeat(64));
        when(snapshotMapper.selectById("panorama-1")).thenReturn(snapshot);

        assertThat(service.reuse(command())).isEmpty();
    }

    @Test
    void changedProfileChecksumRejectsReuse() throws Exception {
        arrangeValidReuse();
        when(profileService.resolve("工程项目")).thenReturn(new ProjectPanoramaPlan(
                11L,
                "工程项目",
                "工程项目全景",
                "f".repeat(64),
                plan().modules()
        ));

        assertThat(service.reuse(command())).isEmpty();
    }

    @Test
    void changedFieldPolicyRejectsReuse() throws Exception {
        arrangeValidReuse();
        when(accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(
                        101L, DATASET_CHECKSUM, "f".repeat(64)
                )
        ));

        assertThat(service.reuse(command())).isEmpty();
    }

    @Test
    void moduleAuthorizationFailureRejectsWholeReuse() throws Exception {
        arrangeValidReuse();
        when(accessService.reauthorize(any()))
                .thenReturn(Optional.of(grant()))
                .thenReturn(Optional.empty());

        assertThat(service.reuse(command())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = DatasetExecutionStatus.class,
            names = {"DENIED", "FAILED", "TIMEOUT"}
    )
    void terminalModuleWithoutSnapshotAlwaysRejectsReuse(
            DatasetExecutionStatus terminalStatus) throws Exception {
        arrangeValidReuse();
        ProjectPanoramaSnapshot aggregate = aggregateSnapshot();
        aggregate.setStatus(PanoramaExecutionState.FAILED.name());
        aggregate.setRequiredComplete(false);
        aggregate.setAllModulesComplete(false);
        when(snapshotMapper.selectById("panorama-1")).thenReturn(aggregate);
        when(moduleMapper.selectList(any())).thenReturn(List.of(
                moduleReference(1L, "CONTRACT", true, 1, terminalStatus, null),
                moduleReference(2L, "BUDGET", false, 2, terminalStatus, null)
        ));

        assertThat(service.reuse(command())).isEmpty();
    }

    @Test
    void authorizationExceptionIsHiddenAsEmptyResult() {
        when(authorizationService.authorize(any())).thenThrow(
                new IllegalArgumentException("selection-token-secret 已失效")
        );

        assertThat(service.reuse(command())).isEmpty();
        verify(snapshotMapper, never()).selectById(any());
    }

    @Test
    void commandAndResultsDoNotExposeSensitiveValuesInToString() throws Exception {
        arrangeValidReuse();
        ProjectPanoramaSnapshotReuseService.ReuseCommand command = command();
        ProjectPanoramaResult result = service.reuse(command).orElseThrow();

        assertThat(command.toString()).doesNotContain(
                "run-sensitive", "user-sensitive", "session-sensitive", "Bearer secret",
                "tenant-secret", "selection-token-secret", "panorama-1", "2026-01-01"
        );
        assertThat(result.toString()).doesNotContain(
                "panorama-1", "合同状态正常", "100万元", "正常",
                "snapshot-contract", "snapshot-budget"
        );
        assertThat(result.modules().get(0).toString()).doesNotContain(
                "100万元", "正常", FIELD_POLICY_CHECKSUM
        );
    }

    @Test
    void sixArgumentModuleConstructorDerivesFrozenSafeChannels() {
        DatasetExecutionResult execution = executionResult();

        ProjectPanoramaResult.ModuleResult module = new ProjectPanoramaResult.ModuleResult(
                "CONTRACT",
                true,
                DatasetExecutionStatus.SUCCESS,
                true,
                "snapshot-contract",
                execution
        );

        assertThat(module.displayFacts()).containsEntry("contractAmount", "100万元");
        assertThat(module.modelFacts()).containsEntry("contractSummary", "正常");
        assertThat(module.fieldPolicyChecksum()).isEqualTo(FIELD_POLICY_CHECKSUM);
        assertThatThrownBy(() -> module.displayFacts().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> module.modelFacts().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void reuseMustNotWrapAuthorizationWorkflowInReadOnlyTransaction() throws Exception {
        Transactional transactional = ProjectPanoramaSnapshotReuseService.class
                .getMethod(
                        "reuse",
                        ProjectPanoramaSnapshotReuseService.ReuseCommand.class
                )
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNull();
    }

    private void arrangeValidReuse() throws Exception {
        when(authorizationService.authorize(any(ProjectPanoramaCommand.class)))
                .thenReturn(subject());
        when(profileService.resolve("工程项目")).thenReturn(plan());
        when(snapshotMapper.selectById("panorama-1")).thenReturn(aggregateSnapshot());
        when(moduleMapper.selectList(any())).thenReturn(moduleReferences());
        when(accessService.reauthorize(any())).thenReturn(Optional.of(grant()));
        when(businessSnapshotMapper.selectById("snapshot-contract"))
                .thenReturn(moduleSnapshot(
                        "snapshot-contract",
                        "CONTRACT",
                        Map.of(
                                "calculation", Map.of("private", "raw-calculation-secret"),
                                "display", Map.of("contractAmount", "100万元"),
                                "export", Map.of("private", "raw-export-secret"),
                                "model", Map.of("contractSummary", "正常")
                        )
                ));
        when(businessSnapshotMapper.selectById("snapshot-budget"))
                .thenReturn(moduleSnapshot(
                        "snapshot-budget",
                        "BUDGET",
                        Map.of(
                                "calculation", Map.of(),
                                "display", Map.of(),
                                "export", Map.of(),
                                "model", Map.of()
                        )
                ));
    }

    private ProjectPanoramaSnapshotReuseService.ReuseCommand command() {
        return new ProjectPanoramaSnapshotReuseService.ReuseCommand(
                "run-sensitive",
                "user-sensitive",
                "session-sensitive",
                "Bearer secret",
                Map.of("tenant", "tenant-secret"),
                "selection-token-secret",
                "panorama-1",
                Map.of("startDate", "2026-01-01", "projectYear", 2026)
        );
    }

    private AuthorizedProjectSubject subject() {
        return new AuthorizedProjectSubject(
                "project-id-1", "P-001", "工程项目", "敏感项目名称"
        );
    }

    private ProjectPanoramaPlan plan() {
        return new ProjectPanoramaPlan(
                11L,
                "工程项目",
                "工程项目全景",
                PROFILE_CHECKSUM,
                List.of(
                        new ProjectPanoramaPlan.Module("CONTRACT", true, 10, 30_000),
                        new ProjectPanoramaPlan.Module("BUDGET", false, 20, 30_000)
                )
        );
    }

    private ProjectPanoramaSnapshot aggregateSnapshot() throws Exception {
        ProjectPanoramaSnapshot snapshot = new ProjectPanoramaSnapshot();
        snapshot.setPanoramaSnapshotId("panorama-1");
        snapshot.setUserId("user-sensitive");
        snapshot.setSessionId("session-sensitive");
        snapshot.setProjectId("project-id-1");
        snapshot.setProjectCode("P-001");
        snapshot.setProjectType("工程项目");
        snapshot.setProfileId(11L);
        snapshot.setProfileChecksum(PROFILE_CHECKSUM);
        snapshot.setQueryHash(queryHash());
        snapshot.setStatus(PanoramaExecutionState.COMPLETE.name());
        snapshot.setRequiredComplete(true);
        snapshot.setAllModulesComplete(true);
        snapshot.setIssuesJson(objectMapper.writeValueAsString(List.of(issue())));
        snapshot.setExpiresAt(NOW.plusHours(1));
        return snapshot;
    }

    private List<ProjectPanoramaSnapshotModule> moduleReferences() {
        return List.of(
                moduleReference(1L, "CONTRACT", true, 1,
                        DatasetExecutionStatus.SUCCESS, "snapshot-contract"),
                moduleReference(2L, "BUDGET", false, 2,
                        DatasetExecutionStatus.EMPTY, "snapshot-budget")
        );
    }

    private ProjectPanoramaSnapshotModule moduleReference(
            Long id,
            String datasetCode,
            boolean required,
            int displayOrder,
            DatasetExecutionStatus status,
            String snapshotId) {
        ProjectPanoramaSnapshotModule reference = new ProjectPanoramaSnapshotModule();
        reference.setId(id);
        reference.setPanoramaSnapshotId("panorama-1");
        reference.setDatasetCode(datasetCode);
        reference.setRequiredFlag(required);
        reference.setDisplayOrder(displayOrder);
        reference.setStatus(status.name());
        reference.setSnapshotId(snapshotId);
        return reference;
    }

    private BusinessSnapshot moduleSnapshot(
            String snapshotId,
            String datasetCode,
            Map<String, Object> channels) throws Exception {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setUserId("user-sensitive");
        snapshot.setSessionId("session-sensitive");
        snapshot.setSubjectType(BusinessSubjectType.PROJECT.name());
        snapshot.setSubjectId("project-id-1");
        snapshot.setDatasetCode(datasetCode);
        snapshot.setQueryHash(queryHash());
        snapshot.setStatus("COMPLETE");
        snapshot.setDataComplete(true);
        snapshot.setFactsJson(objectMapper.writeValueAsString(Map.of(datasetCode, channels)));
        snapshot.setConfigChecksum(DATASET_CHECKSUM);
        snapshot.setFieldPolicyChecksum(FIELD_POLICY_CHECKSUM);
        snapshot.setExpiresAt(NOW.plusMinutes(30));
        return snapshot;
    }

    private BusinessSnapshotAccessService.AccessGrant grant() {
        return new BusinessSnapshotAccessService.AccessGrant(
                101L, DATASET_CHECKSUM, FIELD_POLICY_CHECKSUM
        );
    }

    private ProjectIssueResult issue() {
        return new ProjectIssueResult(
                "CONTRACT_NORMAL",
                IssueMatchStatus.MATCHED,
                "INFO",
                "合同状态正常",
                List.of("contractAmount")
        );
    }

    private String queryHash() {
        return ContentHashUtils.sha256(ReportDatasetValidator.canonicalSafeValue(
                Map.of("startDate", "2026-01-01", "projectCode", "P-001")
        ));
    }

    private DatasetExecutionResult executionResult() {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        "user-sensitive",
                        "session-sensitive",
                        BusinessSubjectType.PROJECT,
                        "project-id-1",
                        "CONTRACT",
                        queryHash(),
                        "wf-contract",
                        1L,
                        DATASET_CHECKSUM,
                        FIELD_POLICY_CHECKSUM
                ),
                DatasetExecutionStatus.SUCCESS,
                true,
                Map.of(
                        "calculation", Map.of("private", "raw-calculation-secret"),
                        "display", Map.of("contractAmount", "100万元"),
                        "export", Map.of("private", "raw-export-secret"),
                        "model", Map.of("contractSummary", "正常")
                ),
                "workflow-run-1",
                null,
                null,
                null,
                "proof"
        );
    }
}
