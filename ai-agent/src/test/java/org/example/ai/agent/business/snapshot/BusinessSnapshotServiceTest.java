package org.example.ai.agent.business.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService.CreateCommand;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService.ItemCommand;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.impl.BusinessSnapshotServiceImpl;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifact;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactMapper;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessSnapshotServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 10, 0);

    @Mock
    private BusinessSnapshotMapper snapshotMapper;

    @Mock
    private BusinessSnapshotItemMapper itemMapper;

    @Mock
    private ResultArtifactMapper artifactMapper;

    @Mock
    private WorkflowRunMapper workflowRunMapper;

    private BusinessSnapshotService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-02T02:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );
        service = new BusinessSnapshotServiceImpl(
                snapshotMapper,
                itemMapper,
                artifactMapper,
                workflowRunMapper,
                new ObjectMapper(),
                clock
        );
    }

    @Test
    void shouldPersistOnlySafeFactsAndExecutionReferences() throws Exception {
        stubSuccessfulInsert();
        ReportDataset dataset = dataset(120);
        ResultArtifact artifact = artifact("artifact-1", NOW.plusHours(3));
        when(artifactMapper.selectOne(any())).thenReturn(artifact);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("projectCode", "XXXT2674040");
        DatasetExecutionResult result = result(
                DatasetExecutionStatus.SUCCESS,
                true,
                safeFacts(Map.of("contractAmount", 8600)),
                "run-1",
                "artifact-1"
        );

        BusinessSnapshot created = service.create(command(dataset, query, result));

        ArgumentCaptor<BusinessSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(BusinessSnapshot.class);
        verify(snapshotMapper).insert(snapshotCaptor.capture());
        BusinessSnapshot saved = snapshotCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getSessionId()).isEqualTo("session-1");
        assertThat(saved.getSubjectType()).isEqualTo("PROJECT");
        assertThat(saved.getSubjectId()).isEqualTo("project-1");
        assertThat(saved.getDatasetCode()).isEqualTo("CONTRACT");
        assertThat(saved.getConfigChecksum()).isEqualTo("config-checksum");
        assertThat(saved.getFieldPolicyChecksum()).isEqualTo("policy-checksum");
        assertThat(saved.getQueryHash()).hasSize(64);
        assertThat(saved.getFactsJson())
                .contains("contractAmount")
                .doesNotContain("Authorization", "rawResponse", "Bearer");
        assertThat(created).isSameAs(saved);

        ArgumentCaptor<BusinessSnapshotItem> itemCaptor =
                ArgumentCaptor.forClass(BusinessSnapshotItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        BusinessSnapshotItem item = itemCaptor.getValue();
        assertThat(item.getSnapshotId()).isEqualTo(saved.getSnapshotId());
        assertThat(item.getStatus()).isEqualTo("SUCCESS");
        assertThat(item.getWorkflowCode()).isEqualTo("contract.query");
        assertThat(item.getWorkflowVersionId()).isEqualTo(7L);
        assertThat(item.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(item.getResultArtifactId()).isEqualTo("artifact-1");
    }

    @Test
    void shouldUseDatasetTtlWhenItIsShorterThanGlobalLimit() {
        stubSuccessfulInsert();
        ReportDataset dataset = dataset(30);
        when(artifactMapper.selectOne(any()))
                .thenReturn(artifact("artifact-1", NOW.plusHours(3)));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());

        BusinessSnapshot snapshot = service.create(command(
                dataset,
                Map.of("year", 2026),
                result(DatasetExecutionStatus.SUCCESS, true, safeFacts(Map.of()), "run-1", "artifact-1")
        ));

        assertThat(snapshot.getExpiresAt()).isEqualTo(NOW.plusMinutes(30));
    }

    @Test
    void shouldNeverRetainSnapshotLongerThanTwentyFourHours() {
        stubSuccessfulInsert();
        ReportDataset dataset = dataset(1440);
        when(artifactMapper.selectOne(any()))
                .thenReturn(artifact("artifact-1", NOW.plusDays(3)));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());

        BusinessSnapshot snapshot = service.create(command(
                dataset,
                Map.of("year", 2026),
                result(DatasetExecutionStatus.SUCCESS, true, safeFacts(Map.of()), "run-1", "artifact-1")
        ));

        assertThat(snapshot.getExpiresAt()).isEqualTo(NOW.plusHours(24));
    }

    @Test
    void shouldClampExpiryToReferencedArtifact() {
        stubSuccessfulInsert();
        ReportDataset dataset = dataset(120);
        when(artifactMapper.selectOne(any()))
                .thenReturn(artifact("artifact-1", NOW.plusMinutes(12)));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());

        BusinessSnapshot snapshot = service.create(command(
                dataset,
                Map.of("year", 2026),
                result(DatasetExecutionStatus.SUCCESS, true, safeFacts(Map.of()), "run-1", "artifact-1")
        ));

        assertThat(snapshot.getExpiresAt()).isEqualTo(NOW.plusMinutes(12));
    }

    @Test
    void shouldRejectArtifactOwnedByAnotherSessionWithoutPersisting() {
        ReportDataset dataset = dataset(120);
        ResultArtifact artifact = artifact("artifact-1", NOW.plusHours(1));
        artifact.setSessionId("another-session");
        when(artifactMapper.selectOne(any())).thenReturn(artifact);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());

        assertThatThrownBy(() -> service.create(command(
                dataset,
                Map.of("year", 2026),
                result(DatasetExecutionStatus.SUCCESS, true, safeFacts(Map.of()), "run-1", "artifact-1")
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结果制品");
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldRejectExpiredOrMismatchedArtifactReference() {
        ReportDataset dataset = dataset(120);
        ResultArtifact expired = artifact("artifact-1", NOW.minusSeconds(1));
        when(artifactMapper.selectOne(any())).thenReturn(expired);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());

        assertThatThrownBy(() -> service.create(command(
                dataset,
                Map.of("year", 2026),
                result(DatasetExecutionStatus.SUCCESS, true, safeFacts(Map.of()), "run-1", "artifact-1")
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过期");
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldValidateSourceSnapshotOwnershipAndExpiry() {
        ReportDataset dataset = dataset(120);
        BusinessSnapshot source = new BusinessSnapshot();
        source.setSnapshotId("source-1");
        source.setUserId("another-user");
        source.setSessionId("session-1");
        source.setSubjectType("PROJECT");
        source.setSubjectId("project-1");
        source.setDatasetCode("CONTRACT");
        source.setStatus("COMPLETE");
        source.setExpiresAt(NOW.plusHours(1));
        when(snapshotMapper.selectOne(any())).thenReturn(source);

        CreateCommand command = new CreateCommand(
                "user-1",
                "session-1",
                "PROJECT",
                "project-1",
                dataset,
                Map.of("year", 2026),
                "source-1",
                List.of(item(result(
                        DatasetExecutionStatus.SUCCESS,
                        true,
                        safeFacts(Map.of()),
                        null,
                        null
                )))
        );

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("来源快照");
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldMapTerminalItemStatusesAndSnapshotCompleteness() {
        stubSuccessfulInsert();
        ReportDataset dataset = dataset(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());
        CreateCommand command = new CreateCommand(
                "user-1",
                "session-1",
                "PROJECT",
                "project-1",
                dataset,
                Map.of("year", 2026),
                null,
                List.of(
                        item(result(DatasetExecutionStatus.SUCCESS, true, safeFacts(Map.of("x", 1)), "run-1", null)),
                        new ItemCommand(
                                "employee-2",
                                "DIRECT",
                                result(DatasetExecutionStatus.TIMEOUT, false, Map.of(), null, null),
                                1,
                                0,
                                1
                        )
                )
        );

        BusinessSnapshot snapshot = service.create(command);

        assertThat(snapshot.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(snapshot.getDataComplete()).isFalse();
        ArgumentCaptor<BusinessSnapshotItem> itemCaptor =
                ArgumentCaptor.forClass(BusinessSnapshotItem.class);
        verify(itemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues())
                .extracting(BusinessSnapshotItem::getStatus)
                .containsExactly("SUCCESS", "TIMEOUT");
    }

    @Test
    void shouldRejectRestrictedAndNonTerminalItems() {
        ReportDataset dataset = dataset(120);

        assertThatThrownBy(() -> service.create(command(
                dataset,
                Map.of("year", 2026),
                result(DatasetExecutionStatus.DENIED, false, Map.of(), null, null)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");
        assertThatThrownBy(() -> service.create(command(
                dataset,
                Map.of("year", 2026),
                result(DatasetExecutionStatus.RUNNING, false, Map.of(), null, null)
        )))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("终态");
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldResolveActualWorkflowVersionWithoutArtifact() {
        stubSuccessfulInsert();
        ReportDataset dataset = dataset(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun());

        service.create(command(
                dataset,
                Map.of("year", 2026),
                result(
                        DatasetExecutionStatus.SUCCESS,
                        true,
                        safeFacts(Map.of("amount", 12)),
                        "run-1",
                        null
                )
        ));

        ArgumentCaptor<BusinessSnapshotItem> itemCaptor =
                ArgumentCaptor.forClass(BusinessSnapshotItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getWorkflowVersionId()).isEqualTo(7L);
        assertThat(itemCaptor.getValue().getWorkflowConfigChecksum())
                .isEqualTo("workflow-checksum");
    }

    @Test
    void shouldCreateFullV13RuntimeSchema() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V13__create_business_snapshot_and_report_task.sql"
        )) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains(
                    "CREATE TABLE ai_business_snapshot",
                    "CREATE TABLE ai_business_snapshot_item",
                    "CREATE TABLE ai_composite_report_task",
                    "CREATE TABLE ai_composite_report_section",
                    "UNIQUE KEY uk_report_request_key",
                    "KEY idx_snapshot_owner_subject",
                    "KEY idx_report_task_claim"
            );
            assertThat(sql).doesNotContain("raw_response", "authorization", "token");
        }
    }

    private CreateCommand command(
            ReportDataset dataset,
            Map<String, Object> query,
            DatasetExecutionResult result) {
        return new CreateCommand(
                "user-1",
                "session-1",
                "PROJECT",
                "project-1",
                dataset,
                query,
                null,
                List.of(item(result))
        );
    }

    private void stubSuccessfulInsert() {
        when(snapshotMapper.insert(any(BusinessSnapshot.class))).thenReturn(1);
        when(itemMapper.insert(any(BusinessSnapshotItem.class))).thenReturn(1);
    }

    private ItemCommand item(DatasetExecutionResult result) {
        return new ItemCommand("dataset", "DIRECT", result, 1, 1, 0);
    }

    private ReportDataset dataset(int ttlMinutes) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode("CONTRACT");
        dataset.setQueryWorkflowCode("contract.query");
        dataset.setTtlMinutes(ttlMinutes);
        dataset.setConfigChecksum("config-checksum");
        dataset.setFieldPolicyChecksum("policy-checksum");
        dataset.setEnabled(true);
        return dataset;
    }

    private ResultArtifact artifact(String id, LocalDateTime expiresAt) {
        ResultArtifact artifact = new ResultArtifact();
        artifact.setId(id);
        artifact.setRunId("run-1");
        artifact.setSessionId("session-1");
        artifact.setUserId("user-1");
        artifact.setWorkflowCode("contract.query");
        artifact.setWorkflowVersionId(7L);
        artifact.setStatus("COMPLETE");
        artifact.setExpiresAt(expiresAt);
        return artifact;
    }

    private WorkflowRun workflowRun() {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        run.setUserId("user-1");
        run.setWorkflowCode("contract.query");
        run.setWorkflowVersionId(7L);
        run.setWorkflowVersionNo(3);
        run.setConfigChecksum("workflow-checksum");
        run.setStatus("SUCCESS");
        return run;
    }

    private Map<String, Object> safeFacts(Map<String, Object> visibleFacts) {
        return Map.of(
                "calculation", visibleFacts,
                "display", visibleFacts,
                "export", visibleFacts,
                "model", visibleFacts
        );
    }

    private DatasetExecutionResult result(
            DatasetExecutionStatus status,
            boolean complete,
            Map<String, Object> facts,
            String runId,
            String artifactId) {
        return new DatasetExecutionResult(
                "CONTRACT",
                status,
                complete,
                facts,
                runId,
                artifactId,
                null,
                null
        );
    }
}
