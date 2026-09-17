package org.example.ai.agent.business.snapshot;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.SharedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer.MissingValue;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.impl.DatasetExecutionProofTestFixture;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService.CreateCommand;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService.ItemCommand;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.impl.BusinessSnapshotServiceImpl;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
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

    private static final String DATASET_CHECKSUM = "a".repeat(64);
    private static final String POLICY_CHECKSUM = "b".repeat(64);
    private static final String WORKFLOW_CHECKSUM = "c".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 10, 0);

    @Mock private BusinessSnapshotMapper snapshotMapper;
    @Mock private BusinessSnapshotItemMapper itemMapper;
    @Mock private ResultArtifactMapper artifactMapper;
    @Mock private WorkflowRunMapper workflowRunMapper;
    @Mock private ReportDatasetMapper datasetMapper;
    @Mock private ReportDatasetFieldMapper datasetFieldMapper;

    private DatasetExecutionProofTestFixture proofFixture;
    private BusinessSnapshotService service;

    @BeforeEach
    void setUp() {
        proofFixture = new DatasetExecutionProofTestFixture();
        service = service(256 * 1024, 1000);
    }

    @Test
    void shouldPersistBoundSafeFactsAndActualExecutionReferences() {
        stubCurrentConfiguration(120);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        when(artifactMapper.selectOne(any())).thenReturn(artifact(NOW.plusHours(3)));

        BusinessSnapshot snapshot = service.create(command(
                query(),
                List.of(item(successResult(query(), "run-1", "artifact-1")))
        ));

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETE");
        assertThat(snapshot.getConfigChecksum()).isEqualTo(DATASET_CHECKSUM);
        assertThat(snapshot.getFieldPolicyChecksum()).isEqualTo(POLICY_CHECKSUM);
        assertThat(snapshot.getQueryHash()).isEqualTo(queryHash(query()));
        assertThat(snapshot.getFactsJson()).contains("amount").doesNotContain("rawResponse");
        ArgumentCaptor<BusinessSnapshotItem> itemCaptor =
                ArgumentCaptor.forClass(BusinessSnapshotItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertThat(itemCaptor.getValue().getWorkflowVersionId()).isEqualTo(7L);
        assertThat(itemCaptor.getValue().getWorkflowConfigChecksum())
                .isEqualTo(WORKFLOW_CHECKSUM);
        ArgumentCaptor<LambdaQueryWrapper<ReportDataset>> datasetQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(datasetMapper).selectOne(datasetQueryCaptor.capture());
        SharedString datasetLastSql = (SharedString) ReflectionTestUtils.getField(
                datasetQueryCaptor.getValue(), "lastSql"
        );
        assertThat(datasetLastSql).isNotNull();
        assertThat(datasetLastSql.getStringValue()).containsIgnoringCase("FOR UPDATE");
    }

    @Test
    void shouldRejectSourceConfigurationQueryAndSubjectMismatch() {
        stubCurrentConfiguration(120);
        DatasetExecutionSource wrongConfig = source(query(), 7L, "d".repeat(64), POLICY_CHECKSUM);
        assertRejected(result(wrongConfig, DatasetExecutionStatus.SUCCESS, safeFacts(), "run-1", null));

        DatasetExecutionSource wrongPolicy = source(query(), 7L, DATASET_CHECKSUM, "d".repeat(64));
        assertRejected(result(wrongPolicy, DatasetExecutionStatus.SUCCESS, safeFacts(), "run-1", null));

        DatasetExecutionSource wrongQuery = source(Map.of("year", 2025), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);
        assertRejected(result(wrongQuery, DatasetExecutionStatus.SUCCESS, safeFacts(), "run-1", null));

        DatasetExecutionSource wrongSubject = new DatasetExecutionSource(
                "user-1", "session-1", BusinessSubjectType.PERSON, "project-1",
                "CONTRACT", queryHash(query()), "contract.query", 7L,
                DATASET_CHECKSUM, POLICY_CHECKSUM
        );
        assertRejected(result(wrongSubject, DatasetExecutionStatus.SUCCESS, safeFacts(), "run-1", null));

        DatasetExecutionSource wrongWorkflow = new DatasetExecutionSource(
                "user-1", "session-1", BusinessSubjectType.PROJECT, "project-1",
                "CONTRACT", queryHash(query()), "other.query", 7L,
                DATASET_CHECKSUM, POLICY_CHECKSUM
        );
        assertRejected(result(wrongWorkflow, DatasetExecutionStatus.SUCCESS, safeFacts(), "run-1", null));
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldRejectUnsignedExecutionResult() {
        stubCurrentConfiguration(120);
        DatasetExecutionResult unsigned = unsignedResult(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS, safeFacts(), "run-1", null, null
        );

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(unsigned)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("完整性证明");
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldRejectTamperedSafeFactsEvenWhenRawHashLooksValid() {
        stubCurrentConfiguration(120);
        DatasetExecutionResult signed = result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS, safeFacts(), "run-1", null
        );
        DatasetExecutionResult tampered = new DatasetExecutionResult(
                signed.source(), signed.status(), signed.dataComplete(),
                safeFacts(8600, "d".repeat(64)), signed.workflowRunId(),
                signed.resultArtifactId(), signed.safeErrorCode(), signed.safeMessage(),
                signed.integrityProof()
        );

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(tampered)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("完整性证明");
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldRejectForgedFactCodeInAnySafeChannel() {
        stubCurrentConfiguration(120);
        Map<String, Object> forged = Map.of(
                "calculation", Map.of("amount", 1),
                "display", Map.of("rawSalary", 99),
                "export", Map.of("amount", 1),
                "model", Map.of("amount", 1)
        );

        assertThatThrownBy(() -> service.create(command(
                query(),
                List.of(item(result(
                        source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                        DatasetExecutionStatus.SUCCESS, forged, "run-1", null
                )))
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("字段策略");
    }

    @Test
    void shouldRejectRawVisibleValueForHashPolicy() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField hashField = field();
        hashField.setMaskStrategy("HASH");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(hashField));

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts("13800138000", "13800138000"),
                "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("脱敏");
    }

    @Test
    void shouldAcceptExpectedHashValue() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField hashField = field();
        hashField.setMaskStrategy("HASH");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(hashField));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        stubSuccessfulInsert();

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts(
                        "13800138000",
                        "a8ace2bb81a21d9b46b51577c4e7a667fa9107fca5f7d72d94a9bc75ac91b5aa"
                ),
                "run-1", null
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldRejectRawVisibleValueForPartialPolicy() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField partialField = field();
        partialField.setMaskStrategy("PARTIAL");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(partialField));

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts("13800138000", "13800138000"),
                "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("脱敏");
    }

    @Test
    void shouldAcceptExpectedPartialValue() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField partialField = field();
        partialField.setMaskStrategy("PARTIAL");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(partialField));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        stubSuccessfulInsert();

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts("13800138000", "*******8000"),
                "run-1", null
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldRejectRawVisibleValueForSummaryOnlyPolicy() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField summaryField = field();
        summaryField.setMaskStrategy("SUMMARY_ONLY");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(summaryField));

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts("13800138000", "13800138000"),
                "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("脱敏");
    }

    @Test
    void shouldAcceptExpectedSummaryOnlyValue() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField summaryField = field();
        summaryField.setMaskStrategy("SUMMARY_ONLY");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(summaryField));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        stubSuccessfulInsert();

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts("13800138000", "仅用于汇总"),
                "run-1", null
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldAcceptMissingValueForCalculableMaskedField() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField hashField = field();
        hashField.setMaskStrategy("HASH");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(hashField));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        stubSuccessfulInsert();

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts(MissingValue.INSTANCE, MissingValue.INSTANCE),
                "run-1", null
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldAcceptMissingValueForNonCalculableVisibleField() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField hashField = field();
        hashField.setCalculable(false);
        hashField.setMaskStrategy("HASH");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(hashField));
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        stubSuccessfulInsert();

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFactsWithoutCalculation(MissingValue.INSTANCE),
                "run-1", null
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldRejectDifferentValuesAcrossVisibleChannels() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField hashField = field();
        hashField.setCalculable(false);
        hashField.setMaskStrategy("HASH");
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(hashField));
        Map<String, Object> inconsistent = Map.of(
                "calculation", Map.of(),
                "display", Map.of("amount", "a".repeat(64)),
                "export", Map.of("amount", "b".repeat(64)),
                "model", Map.of("amount", "a".repeat(64))
        );

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS, inconsistent, "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("可见通道");
    }

    @Test
    void shouldLockSourceSnapshotAndPreventDerivedExpiryExtension() {
        stubCurrentConfiguration(120);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        when(snapshotMapper.selectOne(any())).thenReturn(sourceSnapshot(NOW.plusMinutes(10)));

        BusinessSnapshot created = service.create(new CreateCommand(
                "user-1", "session-1", BusinessSubjectType.PROJECT, "project-1",
                "CONTRACT", query(), "source-1",
                List.of(item(successResult(query(), "run-1", null)))
        ));

        assertThat(created.getExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        ArgumentCaptor<LambdaQueryWrapper<BusinessSnapshot>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(snapshotMapper).selectOne(wrapperCaptor.capture());
        SharedString lastSql = (SharedString) ReflectionTestUtils.getField(
                wrapperCaptor.getValue(), "lastSql"
        );
        assertThat(lastSql).isNotNull();
        assertThat(lastSql.getStringValue()).containsIgnoringCase("FOR UPDATE");
    }

    @Test
    void shouldRejectSourceSnapshotWhenDatasetChecksumChanged() {
        stubCurrentConfiguration(120);
        BusinessSnapshot source = sourceSnapshot(NOW.plusMinutes(10));
        source.setConfigChecksum("d".repeat(64));
        when(snapshotMapper.selectOne(any())).thenReturn(source);

        assertThatThrownBy(() -> service.create(new CreateCommand(
                "user-1", "session-1", BusinessSubjectType.PROJECT, "project-1",
                "CONTRACT", query(), "source-1",
                List.of(item(successResult(query(), "run-1", null)))
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("来源快照");
    }

    @Test
    void shouldCreateFailedSnapshotWhenEveryItemFailedOrTimedOut() {
        stubCurrentConfiguration(120);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("FAILED"));
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        BusinessSnapshot snapshot = service.create(command(query(), List.of(
                item(result(source, DatasetExecutionStatus.FAILED, Map.of(), "run-1", null)),
                new ItemCommand(
                        "batch-2", AssociationType.DIRECT,
                        result(source, DatasetExecutionStatus.TIMEOUT, Map.of(), null, null),
                        1, 0, 1
                )
        )));

        assertThat(snapshot.getStatus()).isEqualTo("FAILED");
        assertThat(snapshot.getDataComplete()).isFalse();
        assertThat(snapshot.getFactsJson()).isEqualTo("{}");
    }

    @Test
    void shouldRejectSnapshotWithoutAnyVerifiableWorkflowRun() {
        stubCurrentConfiguration(120);
        DatasetExecutionSource source = source(query(), null, DATASET_CHECKSUM, POLICY_CHECKSUM);

        assertThatThrownBy(() -> service.create(command(query(), List.of(
                item(result(source, DatasetExecutionStatus.FAILED, Map.of(), null, null)),
                new ItemCommand(
                        "batch-2", AssociationType.DIRECT,
                        result(source, DatasetExecutionStatus.TIMEOUT, Map.of(), null, null),
                        1, 0, 1
                )
        )))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("工作流运行引用");
    }

    @Test
    void shouldCreatePartialSnapshotForMixedTerminalResults() {
        stubCurrentConfiguration(120);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        BusinessSnapshot snapshot = service.create(command(query(), List.of(
                item(successResult(query(), "run-1", null)),
                new ItemCommand(
                        "batch-2", AssociationType.DIRECT,
                        result(source, DatasetExecutionStatus.FAILED, Map.of(), null, null),
                        1, 0, 1
                )
        )));

        assertThat(snapshot.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(snapshot.getDataComplete()).isFalse();
    }

    @Test
    void shouldRejectFailedRunStatusAndFailedArtifactContradictions() {
        stubCurrentConfiguration(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);
        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.FAILED, Map.of(), "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("状态");

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.TIMEOUT, Map.of(), null, "artifact-1"
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("结果制品");
    }

    @Test
    void shouldRejectRunningRunForOrdinaryFailure() {
        stubCurrentConfiguration(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("RUNNING"));
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.FAILED, Map.of(), "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("状态");
    }

    @Test
    void shouldAllowSuccessfulRunForFactMappingFailureOnly() {
        stubCurrentConfiguration(120);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.FAILED, Map.of(), "run-1", null,
                "FACT_MAPPING_FAILED"
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("FAILED");
        verify(itemMapper).insert(any(BusinessSnapshotItem.class));
    }

    @Test
    void shouldAllowPartialSuccessfulRunForFactMappingFailure() {
        stubCurrentConfiguration(120);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("PARTIAL_SUCCESS"));
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.FAILED, Map.of(), "run-1", null,
                "FACT_MAPPING_FAILED"
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void shouldRejectRunningRunForFactMappingFailure() {
        stubCurrentConfiguration(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("RUNNING"));
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.FAILED, Map.of(), "run-1", null,
                "FACT_MAPPING_FAILED"
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("状态");
    }

    @Test
    void shouldRejectTimeoutRunWithoutTimeoutErrorCode() {
        stubCurrentConfiguration(120);
        WorkflowRun run = workflowRun("FAILED");
        run.setErrorCode(null);
        when(workflowRunMapper.selectOne(any())).thenReturn(run);
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.TIMEOUT, Map.of(), "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("超时");
    }

    @Test
    void shouldAcceptFailedRunWithNormalizedTimeoutErrorCode() {
        stubCurrentConfiguration(120);
        stubSuccessfulInsert();
        WorkflowRun run = workflowRun("FAILED");
        run.setErrorCode(" node_timeout ");
        when(workflowRunMapper.selectOne(any())).thenReturn(run);
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);

        BusinessSnapshot snapshot = service.create(command(query(), List.of(item(result(
                source, DatasetExecutionStatus.TIMEOUT, Map.of(), "run-1", null,
                "QUERY_TIMEOUT"
        )))));

        assertThat(snapshot.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void shouldUseArtifactDatasetAndGlobalExpiryMinimum() {
        stubCurrentConfiguration(1440);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        when(artifactMapper.selectOne(any())).thenReturn(artifact(NOW.plusMinutes(12)));

        BusinessSnapshot snapshot = service.create(command(
                query(),
                List.of(item(successResult(query(), "run-1", "artifact-1")))
        ));
        assertThat(snapshot.getExpiresAt()).isEqualTo(NOW.plusMinutes(12));
    }

    /**
     * 数据集TTL只控制新鲜期，小型安全事实最长保留24小时供明确历史引用。
     */
    @Test
    void shouldRetainInlineSnapshotForGlobalRetentionPeriod() {
        stubCurrentConfiguration(10);
        stubSuccessfulInsert();
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));

        BusinessSnapshot snapshot = service.create(command(
                query(), List.of(item(successResult(query(), "run-1", null)))
        ));

        assertThat(snapshot.getExpiresAt()).isEqualTo(NOW.plusHours(24));
    }

    @Test
    void shouldRejectInvalidLengthsAndChecksumsBeforeInsert() {
        ReportDataset invalid = dataset(120);
        invalid.setConfigChecksum("short");
        when(datasetMapper.selectOne(any())).thenReturn(invalid);
        assertThatThrownBy(() -> service.create(command(
                query(), List.of(item(successResult(query(), "run-1", null)))
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("校验和");

        stubCurrentConfiguration(120);
        assertThatThrownBy(() -> service.create(command(
                query(),
                List.of(new ItemCommand(
                        "x".repeat(129), AssociationType.DIRECT,
                        successResult(query(), "run-1", null), 1, 1, 0
                ))
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("itemKey");
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldRejectCountSumOverflow() {
        stubCurrentConfiguration(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));

        assertThatThrownBy(() -> service.create(command(query(), List.of(new ItemCommand(
                "dataset", AssociationType.DIRECT,
                successResult(query(), "run-1", null),
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE
        ))))).isInstanceOf(BusinessException.class).hasMessageContaining("之和");
    }

    @Test
    void shouldRejectFactsBeyondConfiguredUtf8ByteLimit() {
        service = service(80, 1000);
        stubCurrentConfiguration(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("SUCCESS"));
        Map<String, Object> facts = safeFacts("金额".repeat(100));

        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result(
                source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS, facts, "run-1", null
        )))))).isInstanceOf(BusinessException.class).hasMessageContaining("ResultArtifact");
    }

    @Test
    void shouldRejectQueryBeyondConfiguredUtf8ByteLimit() {
        service = service(256 * 1024, 80, 1000);
        stubCurrentConfiguration(120);
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun("FAILED"));
        Map<String, Object> largeQuery = Map.of("keyword", "项目".repeat(100));
        DatasetExecutionSource source = source(
                largeQuery, 7L, DATASET_CHECKSUM, POLICY_CHECKSUM
        );

        assertThatThrownBy(() -> service.create(command(
                largeQuery,
                List.of(item(result(
                        source, DatasetExecutionStatus.FAILED, Map.of(), "run-1", null
                )))
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("查询条件");
    }

    @Test
    void shouldFailClosedWhenFieldPolicyBooleanIsNull() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(120));
        ReportDatasetField invalid = field();
        invalid.setModelVisible(null);
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(invalid));

        assertThatThrownBy(() -> service.create(command(
                query(),
                List.of(item(successResult(query(), "run-1", null)))
        ))).isInstanceOf(BusinessException.class).hasMessageContaining("布尔值");
    }

    @Test
    void shouldAllowMoreThanTwoHundredItemsButRejectConfiguredMaximum() {
        service = service(256 * 1024, 201);
        List<ItemCommand> items = new ArrayList<>();
        DatasetExecutionSource source = source(query(), 7L, DATASET_CHECKSUM, POLICY_CHECKSUM);
        for (int index = 0; index < 202; index++) {
            items.add(new ItemCommand(
                    "item-" + index, AssociationType.DIRECT,
                    result(source, DatasetExecutionStatus.FAILED, Map.of(), null, null),
                    1, 0, 1
            ));
        }

        assertThatThrownBy(() -> service.create(command(query(), items)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("201");
    }

    @Test
    void shouldCreateFullV13RuntimeSchemaWithoutDuplicateSectionIndex() throws Exception {
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
                    "RUNNING",
                    "RETRY",
                    "CANCELLED"
            );
            assertThat(count(sql, "idx_report_section_order")).isEqualTo(0);
            assertThat(sql).contains("UNIQUE KEY uk_report_section_order");
        }
    }

    private BusinessSnapshotService service(int maxFactBytes, int maxItems) {
        return service(maxFactBytes, 64 * 1024, maxItems);
    }

    private BusinessSnapshotService service(
            int maxFactBytes,
            int maxQueryBytes,
            int maxItems) {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-02T02:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );
        return new BusinessSnapshotServiceImpl(
                snapshotMapper, itemMapper, artifactMapper, workflowRunMapper,
                datasetMapper, datasetFieldMapper,
                proofFixture.verifier(),
                new BusinessFactSanitizer(new ReportDatasetValidator()),
                new ObjectMapper(), clock,
                maxFactBytes, maxQueryBytes, maxItems
        );
    }

    private void stubCurrentConfiguration(int ttlMinutes) {
        when(datasetMapper.selectOne(any())).thenReturn(dataset(ttlMinutes));
        when(datasetFieldMapper.selectList(any())).thenReturn(List.of(field()));
    }

    private void stubSuccessfulInsert() {
        when(snapshotMapper.insert(any(BusinessSnapshot.class))).thenReturn(1);
        when(itemMapper.insert(any(BusinessSnapshotItem.class))).thenReturn(1);
    }

    private void assertRejected(DatasetExecutionResult result) {
        assertThatThrownBy(() -> service.create(command(query(), List.of(item(result)))))
                .isInstanceOf(BusinessException.class);
    }

    private CreateCommand command(Map<String, Object> canonicalQuery, List<ItemCommand> items) {
        return new CreateCommand(
                "user-1", "session-1", BusinessSubjectType.PROJECT, "project-1",
                "CONTRACT", canonicalQuery, null, items
        );
    }

    private ItemCommand item(DatasetExecutionResult result) {
        return new ItemCommand("dataset", AssociationType.DIRECT, result, 1, 1, 0);
    }

    private DatasetExecutionResult successResult(
            Map<String, Object> canonicalQuery,
            String runId,
            String artifactId) {
        return result(
                source(canonicalQuery, 7L, DATASET_CHECKSUM, POLICY_CHECKSUM),
                DatasetExecutionStatus.SUCCESS,
                safeFacts(),
                runId,
                artifactId
        );
    }

    private DatasetExecutionResult result(
            DatasetExecutionSource source,
            DatasetExecutionStatus status,
            Map<String, Object> facts,
            String runId,
            String artifactId) {
        return result(source, status, facts, runId, artifactId, null);
    }

    private DatasetExecutionResult result(
            DatasetExecutionSource source,
            DatasetExecutionStatus status,
            Map<String, Object> facts,
            String runId,
            String artifactId,
            String safeErrorCode) {
        return proofFixture.sign(unsignedResult(
                source, status, status == DatasetExecutionStatus.SUCCESS,
                facts, runId, artifactId, safeErrorCode, null
        ));
    }

    private DatasetExecutionResult unsignedResult(
            DatasetExecutionSource source,
            DatasetExecutionStatus status,
            Map<String, Object> facts,
            String runId,
            String artifactId,
            String safeErrorCode) {
        return unsignedResult(
                source, status, status == DatasetExecutionStatus.SUCCESS,
                facts, runId, artifactId, safeErrorCode, null
        );
    }

    private DatasetExecutionResult unsignedResult(
            DatasetExecutionSource source,
            DatasetExecutionStatus status,
            boolean dataComplete,
            Map<String, Object> facts,
            String runId,
            String artifactId,
            String safeErrorCode,
            String safeMessage) {
        return new DatasetExecutionResult(
                source, status, dataComplete, facts, runId, artifactId,
                safeErrorCode, safeMessage, null
        );
    }

    private DatasetExecutionSource source(
            Map<String, Object> canonicalQuery,
            Long workflowVersionId,
            String datasetChecksum,
            String policyChecksum) {
        return new DatasetExecutionSource(
                "user-1", "session-1", BusinessSubjectType.PROJECT, "project-1",
                "CONTRACT", queryHash(canonicalQuery), "contract.query", workflowVersionId,
                datasetChecksum, policyChecksum
        );
    }

    private String queryHash(Map<String, Object> canonicalQuery) {
        return ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(canonicalQuery)
        );
    }

    private Map<String, Object> query() {
        return Map.of("year", 2026);
    }

    private Map<String, Object> safeFacts() {
        return safeFacts(8600);
    }

    private Map<String, Object> safeFacts(Object amount) {
        return safeFacts(amount, amount);
    }

    private Map<String, Object> safeFacts(Object calculationValue, Object visibleValue) {
        Map<String, Object> calculation = Map.of("amount", calculationValue);
        Map<String, Object> visible = Map.of("amount", visibleValue);
        return Map.of(
                "calculation", calculation,
                "display", visible,
                "export", visible,
                "model", visible
        );
    }

    private Map<String, Object> safeFactsWithoutCalculation(Object visibleValue) {
        Map<String, Object> visible = Map.of("amount", visibleValue);
        return Map.of(
                "calculation", Map.of(),
                "display", visible,
                "export", visible,
                "model", visible
        );
    }

    private ReportDataset dataset(int ttlMinutes) {
        ReportDataset dataset = new ReportDataset();
        dataset.setId(1L);
        dataset.setDatasetCode("CONTRACT");
        dataset.setQueryWorkflowCode("contract.query");
        dataset.setTtlMinutes(ttlMinutes);
        dataset.setConfigChecksum(DATASET_CHECKSUM);
        dataset.setFieldPolicyChecksum(POLICY_CHECKSUM);
        dataset.setEnabled(true);
        return dataset;
    }

    private ReportDatasetField field() {
        ReportDatasetField field = new ReportDatasetField();
        field.setDatasetId(1L);
        field.setFactCode("amount");
        field.setCalculable(true);
        field.setDisplayable(true);
        field.setExportable(true);
        field.setModelVisible(true);
        field.setFactType("STRING");
        field.setMaskStrategy("NONE");
        field.setGrain("PROJECT");
        return field;
    }

    private WorkflowRun workflowRun(String status) {
        WorkflowRun run = new WorkflowRun();
        run.setRunId("run-1");
        run.setUserId("user-1");
        run.setWorkflowCode("contract.query");
        run.setWorkflowVersionId(7L);
        run.setWorkflowVersionNo(3);
        run.setConfigChecksum(WORKFLOW_CHECKSUM);
        run.setStatus(status);
        run.setErrorCode("FAILED".equals(status) ? "DOWNSTREAM_FAILURE" : null);
        return run;
    }

    private ResultArtifact artifact(LocalDateTime expiresAt) {
        ResultArtifact artifact = new ResultArtifact();
        artifact.setId("artifact-1");
        artifact.setRunId("run-1");
        artifact.setSessionId("session-1");
        artifact.setUserId("user-1");
        artifact.setWorkflowCode("contract.query");
        artifact.setWorkflowVersionId(7L);
        artifact.setStatus("COMPLETE");
        artifact.setExpiresAt(expiresAt);
        return artifact;
    }

    private BusinessSnapshot sourceSnapshot(LocalDateTime expiresAt) {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId("source-1");
        snapshot.setUserId("user-1");
        snapshot.setSessionId("session-1");
        snapshot.setSubjectType("PROJECT");
        snapshot.setSubjectId("project-1");
        snapshot.setDatasetCode("CONTRACT");
        snapshot.setConfigChecksum(DATASET_CHECKSUM);
        snapshot.setFieldPolicyChecksum(POLICY_CHECKSUM);
        snapshot.setStatus("COMPLETE");
        snapshot.setExpiresAt(expiresAt);
        return snapshot;
    }

    private int count(String value, String needle) {
        return value.split(needle, -1).length - 1;
    }
}
