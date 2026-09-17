package org.example.ai.agent.business.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.BusinessSnapshotDerivationService.DeriveCommand;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.PersonBusinessQueryService.ProjectAssociationContext;
import org.example.ai.agent.business.person.ProjectPeriodContextService;
import org.example.ai.agent.business.person.ProjectRecordAssociationService;
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
                new BusinessFactSanitizer(new ReportDatasetValidator()),
                new ProjectRecordAssociationService(), objectMapper, fixedClock()
        );
        when(accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(9L, CONFIG, POLICY, 60)
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

    @Test
    void shouldRebuildMaskedChannelsFromDerivedCalculationFacts() throws Exception {
        ReportDatasetField partial = field();
        partial.setMaskStrategy("PARTIAL");
        when(fieldMapper.selectList(any())).thenReturn(List.of(partial));
        BusinessSnapshot source = sourceSnapshot();
        source.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "employee-E100", Map.of(
                        "calculation", Map.of("attendanceDetails", List.of(
                                record("2026-01-02", 100), record("2026-04-02", 300)
                        )),
                        "display", Map.of("attendanceDetails", "old-mask"),
                        "export", Map.of("attendanceDetails", "old-mask"),
                        "model", Map.of("attendanceDetails", "old-mask")
                )
        )));
        when(snapshotMapper.selectOne(any())).thenReturn(source);

        BusinessSnapshot child = service.derive(command());

        Map<?, ?> root = objectMapper.readValue(child.getFactsJson(), Map.class);
        Map<?, ?> item = (Map<?, ?>) root.get("employee-E100");
        assertThat(((Map<?, ?>) item.get("calculation")).get("attendanceDetails"))
                .isInstanceOf(List.class);
        assertThat(((Map<?, ?>) item.get("display")).get("attendanceDetails"))
                .isInstanceOf(String.class)
                .isNotEqualTo("old-mask");
    }

    @Test
    void shouldRejectOversizedCanonicalQueryBeforeAccessOrInsert() {
        DeriveCommand oversized = command(Map.of(
                "startDate", "2026-01-01", "endDate", "2026-03-31",
                "grain", "QUARTER", "filter", "中".repeat(70_000)
        ), "QUARTER");

        assertThatThrownBy(() -> service.derive(oversized))
                .isInstanceOf(BusinessException.class);
        verify(accessService, never()).reauthorize(any());
        verify(snapshotMapper, never()).insert(any(BusinessSnapshot.class));
    }

    @Test
    void shouldApplyHashAndSummaryOnlyPoliciesToDerivedVisibleChannels() throws Exception {
        BusinessSnapshot source = sourceSnapshot();
        source.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "employee-E100", envelope(List.of(record("2026-01-02", 100)))
        )));
        when(snapshotMapper.selectOne(any())).thenReturn(source);

        ReportDatasetField hash = field();
        hash.setMaskStrategy("HASH");
        when(fieldMapper.selectList(any())).thenReturn(List.of(hash));
        Object hashed = visibleValue(service.derive(command()));
        assertThat(hashed).isInstanceOf(String.class);
        assertThat((String) hashed).hasSize(64);

        ReportDatasetField summary = field();
        summary.setMaskStrategy("SUMMARY_ONLY");
        when(fieldMapper.selectList(any())).thenReturn(List.of(summary));
        assertThat(visibleValue(service.derive(command())))
                .isEqualTo(BusinessFactSanitizer.SUMMARY_ONLY_VALUE);
    }

    @Test
    void shouldDeriveOnlyDirectProjectFactsAndPersistAssociationAuditCounts() throws Exception {
        ReportDatasetField travelField = field();
        travelField.setFactCode(PersonBusinessQueryService.TRAVEL_RECORDS);
        when(fieldMapper.selectList(any())).thenReturn(List.of(travelField));
        BusinessSnapshot source = sourceSnapshot();
        source.setDatasetCode("PERSON_TRAVEL");
        source.setQueryJson(objectMapper.writeValueAsString(Map.of(
                "startDate", "2026-03-01", "endDate", "2026-03-31"
        )));
        source.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "person", Map.of(
                        "calculation", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, List.of(
                                travel("T-1", "XXXT2674040", "2026-03-01T08:00:00", "10.00"),
                                travel("T-2", null, "2026-03-02T08:00:00", "20.00"),
                                travel("T-3", null, null, "30.00"),
                                travel("T-4", "OTHER", "2026-03-03T08:00:00", "40.00")
                        )),
                        "display", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, List.of()),
                        "export", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, List.of()),
                        "model", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, List.of())
                )
        )));
        BusinessSnapshotItem item = sourceItem();
        item.setItemKey("person");
        when(snapshotMapper.selectOne(any())).thenReturn(source);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));

        BusinessSnapshotDerivationService.ProjectAssociationDerivation result =
                service.deriveProjectAssociation(projectAssociationCommand());

        assertThat(result.snapshot().getSourceSnapshotId()).isEqualTo("source-1");
        assertThat(result.directCalculationFacts().get(PersonBusinessQueryService.TRAVEL_RECORDS))
                .asList().singleElement().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("recordId", "T-1")
                .containsEntry("amount", new java.math.BigDecimal("10.00"));
        assertThat(result.summary())
                .isEqualTo(new PersonBusinessQueryService.AssociationSummary(1, 1, 1, 1));
        assertThat(result.labels()).containsExactly(
                "存在项目人员期间关联记录，仅供上下文参考，不计入项目直接统计",
                "部分记录的项目关联无法确认，未计入项目直接统计"
        );
        ArgumentCaptor<BusinessSnapshotItem> items =
                ArgumentCaptor.forClass(BusinessSnapshotItem.class);
        verify(itemMapper, org.mockito.Mockito.times(4)).insert(items.capture());
        assertThat(items.getAllValues()).extracting(
                BusinessSnapshotItem::getAssociationType,
                BusinessSnapshotItem::getTotalCount
        ).containsExactly(
                org.assertj.core.groups.Tuple.tuple("DIRECT", 1),
                org.assertj.core.groups.Tuple.tuple("PROJECT_PERSON_PERIOD", 1),
                org.assertj.core.groups.Tuple.tuple("UNKNOWN", 1),
                org.assertj.core.groups.Tuple.tuple("UNRELATED", 1)
        );

        Map<?, ?> root = objectMapper.readValue(result.snapshot().getFactsJson(), Map.class);
        assertThat(root.keySet().stream().map(Object::toString).toList())
                .containsExactly("direct");
        Map<?, ?> direct = (Map<?, ?>) root.get("direct");
        assertThat(direct.keySet().stream().map(Object::toString).toList())
                .containsExactlyInAnyOrder(
                "calculation", "display", "export", "model"
        );
        for (String channel : List.of("calculation", "display", "export", "model")) {
            Map<?, ?> facts = (Map<?, ?>) direct.get(channel);
            List<?> records = (List<?>) facts.get(PersonBusinessQueryService.TRAVEL_RECORDS);
            assertThat(records).singleElement()
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                    .containsEntry("recordId", "T-1");
        }
    }

    @Test
    void shouldBindAuthorizedProjectScopeIntoDerivedQueryIdentity() throws Exception {
        Map<String, Object> sourceQuery = Map.of(
                "startDate", "2026-03-01", "endDate", "2026-03-31",
                "projectCode", "CALLER-SPOOFED"
        );
        prepareProjectTravelSource(sourceQuery, List.of(
                travel("T-1", "PROJECT-A", "2026-03-01T08:00:00", "10.00")
        ));

        BusinessSnapshot projectA = service.deriveProjectAssociation(
                projectAssociationCommand(projectContext("PROJECT-A", "P-A"), sourceQuery)
        ).snapshot();
        BusinessSnapshot projectB = service.deriveProjectAssociation(
                projectAssociationCommand(projectContext("PROJECT-B", "P-B"), sourceQuery)
        ).snapshot();

        assertThat(projectA.getQueryHash()).isNotEqualTo(projectB.getQueryHash());
        Map<?, ?> savedQuery = objectMapper.readValue(projectA.getQueryJson(), Map.class);
        assertThat(savedQuery.get("projectCode")).isEqualTo("PROJECT-A");
        assertThat(savedQuery.get("projectId")).isEqualTo("P-A");
        assertThat(savedQuery.get("periodStart")).isEqualTo("2026-03-01");
        assertThat(savedQuery.get("periodEnd")).isEqualTo("2026-03-31");
        verify(accessService, org.mockito.Mockito.times(2)).reauthorize(
                org.mockito.ArgumentMatchers.argThat(command ->
                        "CALLER-SPOOFED".equals(command.canonicalQuery().get("projectCode")))
        );
    }

    @Test
    void invalidOccurrenceDateIsUnknownAndNeverExported() throws Exception {
        Map<String, Object> sourceQuery = Map.of(
                "startDate", "2026-03-01", "endDate", "2026-03-31"
        );
        prepareProjectTravelSource(sourceQuery, List.of(
                travel("T-1", null, "2026-03-01-invalid", "10.00")
        ));

        BusinessSnapshotDerivationService.ProjectAssociationDerivation result =
                service.deriveProjectAssociation(
                        projectAssociationCommand(projectContext("PROJECT-A", "P-A"), sourceQuery)
                );

        assertThat(result.summary())
                .isEqualTo(new PersonBusinessQueryService.AssociationSummary(0, 0, 1, 0));
        assertThat(result.directCalculationFacts()
                .get(PersonBusinessQueryService.TRAVEL_RECORDS)).asList().isEmpty();
        assertThat(result.labels()).containsExactly(
                "部分记录的项目关联无法确认，未计入项目直接统计"
        );
    }

    private DeriveCommand command() {
        return command(Map.of(
                "startDate", "2026-01-01", "endDate", "2026-03-31", "grain", "QUARTER"
        ), "QUARTER");
    }

    private BusinessSnapshotDerivationService.ProjectAssociationCommand
            projectAssociationCommand() {
        return projectAssociationCommand(
                projectContext("XXXT2674040", "P-1"),
                Map.of("startDate", "2026-03-01", "endDate", "2026-03-31")
        );
    }

    private BusinessSnapshotDerivationService.ProjectAssociationCommand
            projectAssociationCommand(
            ProjectAssociationContext context,
            Map<String, Object> targetQuery) {
        return new BusinessSnapshotDerivationService.ProjectAssociationCommand(
                "agent-1", "user-1", "session-1", "Bearer current", Map.of(),
                "PERSON_TRAVEL", "E100", "source-1",
                targetQuery, DatasetType.TRAVEL, context
        );
    }

    private ProjectAssociationContext projectContext(String projectCode, String projectId) {
        return new ProjectAssociationContext(
                projectCode, projectId,
                java.time.LocalDate.of(2026, 3, 1),
                java.time.LocalDate.of(2026, 3, 31),
                List.of(new ProjectPeriodContextService.MembershipPeriod(
                        java.time.LocalDate.of(2026, 3, 1),
                        java.time.LocalDate.of(2026, 3, 31)
                )),
                true
        );
    }

    private void prepareProjectTravelSource(
            Map<String, Object> sourceQuery,
            List<Map<String, Object>> records) throws Exception {
        ReportDatasetField travelField = field();
        travelField.setFactCode(PersonBusinessQueryService.TRAVEL_RECORDS);
        when(fieldMapper.selectList(any())).thenReturn(List.of(travelField));
        BusinessSnapshot source = sourceSnapshot();
        source.setDatasetCode("PERSON_TRAVEL");
        source.setQueryJson(objectMapper.writeValueAsString(sourceQuery));
        source.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "person", Map.of(
                        "calculation", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, records),
                        "display", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, List.of()),
                        "export", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, List.of()),
                        "model", Map.of(PersonBusinessQueryService.TRAVEL_RECORDS, List.of())
                )
        )));
        BusinessSnapshotItem item = sourceItem();
        item.setItemKey("person");
        when(snapshotMapper.selectOne(any())).thenReturn(source);
        when(itemMapper.selectList(any())).thenReturn(List.of(item));
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
        field.setMaskStrategy("NONE");
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

    private Object visibleValue(BusinessSnapshot snapshot) throws Exception {
        Map<?, ?> root = objectMapper.readValue(snapshot.getFactsJson(), Map.class);
        Map<?, ?> item = (Map<?, ?>) root.get("employee-E100");
        return ((Map<?, ?>) item.get("display")).get("attendanceDetails");
    }

    private Map<String, Object> record(String date, int amount) {
        return Map.of("date", date, "amount", amount);
    }

    private Map<String, Object> travel(
            String recordId,
            String projectCode,
            String startAt,
            String amount) {
        Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("recordId", recordId);
        record.put("projectCode", projectCode);
        record.put("startAt", startAt);
        record.put("approvalStatus", "APPROVED");
        record.put("amount", new java.math.BigDecimal(amount));
        return record;
    }

    private Clock fixedClock() {
        return Clock.fixed(
                Instant.parse("2026-09-02T02:00:00Z"), ZoneId.of("Asia/Shanghai")
        );
    }
}
