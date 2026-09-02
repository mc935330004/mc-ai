package org.example.ai.agent.business.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.BusinessSnapshotDerivationService.DeriveCommand;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessSnapshotDerivationServiceTest {

    private static final String CONFIG = "a".repeat(64);
    private static final String POLICY = "b".repeat(64);
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2026, 9, 2, 18, 0);

    private BusinessSnapshotMapper snapshotMapper;
    private BusinessSnapshotItemMapper itemMapper;
    private ReportDatasetFieldMapper fieldMapper;
    private BusinessSnapshotAccessService accessService;
    private ObjectMapper objectMapper;
    private BusinessSnapshotDerivationService service;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(BusinessSnapshotMapper.class);
        itemMapper = mock(BusinessSnapshotItemMapper.class);
        fieldMapper = mock(ReportDatasetFieldMapper.class);
        accessService = mock(BusinessSnapshotAccessService.class);
        objectMapper = new ObjectMapper();
        service = new BusinessSnapshotDerivationService(
                snapshotMapper, itemMapper, fieldMapper, accessService,
                objectMapper, fixedClock()
        );
        when(accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(9L, CONFIG, POLICY)
        ));
        when(fieldMapper.selectList(any())).thenReturn(List.of(field()));
        when(snapshotMapper.selectOne(any())).thenReturn(sourceSnapshot());
        when(itemMapper.selectList(any())).thenReturn(List.of(sourceItem()));
        when(snapshotMapper.insert(any(BusinessSnapshot.class))).thenReturn(1);
        when(itemMapper.insert(any(BusinessSnapshotItem.class))).thenReturn(1);
    }

    @Test
    void shouldFilterDailyFactsAndRecalculateTotalsWithoutExtendingExpiry() throws Exception {
        List<Map<String, Object>> fact = List.of(
                record("2026-01-02", 100),
                record("2026-04-02", 300)
        );
        BusinessSnapshot source = sourceSnapshot();
        source.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "employee-E100", envelope(fact)
        )));
        when(snapshotMapper.selectOne(any())).thenReturn(source);

        BusinessSnapshot child = service.derive(command());

        assertThat(child.getSourceSnapshotId()).isEqualTo("source-1");
        assertThat(child.getExpiresAt()).isEqualTo(EXPIRES);
        Map<?, ?> root = objectMapper.readValue(child.getFactsJson(), Map.class);
        Map<?, ?> item = (Map<?, ?>) root.get("employee-E100");
        Map<?, ?> calculation = (Map<?, ?>) item.get("calculation");
        List<?> derivedFact = (List<?>) calculation.get("attendanceDetails");
        assertThat(derivedFact).hasSize(1);

        ArgumentCaptor<BusinessSnapshotItem> childItem =
                ArgumentCaptor.forClass(BusinessSnapshotItem.class);
        verify(itemMapper).insert(childItem.capture());
        assertThat(childItem.getValue().getTotalCount()).isEqualTo(1);
        assertThat(childItem.getValue().getResultArtifactId()).isNull();
    }

    @Test
    void shouldRejectScopeBroadeningInsufficientGrainAndUnknownFacts() throws Exception {
        DeriveCommand broader = command(Map.of(
                "startDate", "2025-01-01", "endDate", "2026-12-31", "grain", "YEAR"
        ), "YEAR");
        assertThatThrownBy(() -> service.derive(broader))
                .isInstanceOf(BusinessException.class);

        ReportDatasetField monthly = field();
        monthly.setGrain("MONTH");
        when(fieldMapper.selectList(any())).thenReturn(List.of(monthly));
        assertThatThrownBy(() -> service.derive(command(
                Map.of("startDate", "2026-01-01", "endDate", "2026-01-01", "grain", "DAY"),
                "DAY"
        ))).isInstanceOf(BusinessException.class);

        when(fieldMapper.selectList(any())).thenReturn(List.of(field()));
        BusinessSnapshot source = sourceSnapshot();
        source.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "employee-E100", Map.of(
                        "calculation", Map.of("rawSecret", List.of(record("2026-01-02", 1))),
                        "display", Map.of(), "export", Map.of(), "model", Map.of()
                )
        )));
        when(snapshotMapper.selectOne(any())).thenReturn(source);
        assertThatThrownBy(() -> service.derive(command()))
                .isInstanceOf(BusinessException.class);
        verify(snapshotMapper, never()).deleteById(any(java.io.Serializable.class));

        BusinessSnapshot staleAggregate = sourceSnapshot();
        staleAggregate.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "employee-E100", envelope(Map.of(
                        "records", List.of(record("2026-01-02", 1)),
                        "totalAmount", 999
                ))
        )));
        when(snapshotMapper.selectOne(any())).thenReturn(staleAggregate);
        assertThatThrownBy(() -> service.derive(command()))
                .isInstanceOf(BusinessException.class);
    }

    private DeriveCommand command() {
        return command(Map.of(
                "startDate", "2026-01-01", "endDate", "2026-03-31", "grain", "QUARTER"
        ), "QUARTER");
    }

    private DeriveCommand command(Map<String, Object> target, String grain) {
        return new DeriveCommand(
                "agent-1", "user-1", "session-1", "Bearer current", Map.of(),
                BusinessSubjectType.PERSON, "E100", "ATTENDANCE", "source-1",
                target, AssociationType.DIRECT, grain, Set.of("attendanceDetails")
        );
    }

    private BusinessSnapshot sourceSnapshot() {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId("source-1");
        snapshot.setUserId("user-1");
        snapshot.setSessionId("session-1");
        snapshot.setSubjectType("PERSON");
        snapshot.setSubjectId("E100");
        snapshot.setDatasetCode("ATTENDANCE");
        snapshot.setQueryJson("{\"grain\":\"YEAR\",\"year\":2026}");
        snapshot.setStatus("COMPLETE");
        snapshot.setDataComplete(true);
        snapshot.setConfigChecksum(CONFIG);
        snapshot.setFieldPolicyChecksum(POLICY);
        snapshot.setExpiresAt(EXPIRES);
        snapshot.setFactsJson("{}");
        return snapshot;
    }

    private BusinessSnapshotItem sourceItem() {
        BusinessSnapshotItem item = new BusinessSnapshotItem();
        item.setSnapshotId("source-1");
        item.setItemKey("employee-E100");
        item.setWorkflowCode("attendance.query");
        item.setWorkflowVersionId(8L);
        item.setWorkflowVersionNo(2);
        item.setWorkflowConfigChecksum("c".repeat(64));
        item.setWorkflowRunId("run-1");
        item.setStatus("SUCCESS");
        item.setAssociationType("DIRECT");
        item.setTotalCount(2);
        item.setSuccessCount(2);
        item.setFailureCount(0);
        return item;
    }

    private ReportDatasetField field() {
        ReportDatasetField field = new ReportDatasetField();
        field.setFactCode("attendanceDetails");
        field.setFactType("DATE_RECORD_LIST");
        field.setCalculable(true);
        field.setDisplayable(true);
        field.setExportable(true);
        field.setModelVisible(true);
        field.setFilterable(true);
        field.setGrain("DAY");
        return field;
    }

    private Map<String, Object> envelope(Object fact) {
        return Map.of(
                "calculation", Map.of("attendanceDetails", fact),
                "display", Map.of("attendanceDetails", fact),
                "export", Map.of("attendanceDetails", fact),
                "model", Map.of("attendanceDetails", fact)
        );
    }

    private Map<String, Object> record(String date, int amount) {
        return Map.of("date", date, "amount", amount);
    }

    private Clock fixedClock() {
        return Clock.fixed(
                Instant.parse("2026-09-02T02:00:00Z"), ZoneId.of("Asia/Shanghai")
        );
    }
}
