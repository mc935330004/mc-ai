package org.example.ai.agent.business.person;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Command;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetPlan;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Result;
import org.example.ai.agent.business.person.model.AttendanceDayResult;
import org.example.ai.agent.business.snapshot.BusinessSnapshotMatcher;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService;
import org.example.ai.agent.business.snapshot.SnapshotMatchDecision;
import org.example.ai.agent.business.snapshot.SnapshotMatchResult;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.business.subject.AuthorizedPersonDirectoryService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonBusinessQueryServiceTest {

    private static final String USER_ID = "user-1";
    private static final String SESSION_ID = "session-1";
    private static final String EMPLOYEE_NO = "E1001";

    @Mock
    private AuthorizedPersonDirectoryService personDirectoryService;
    @Mock
    private BusinessSnapshotMatcher snapshotMatcher;
    @Mock
    private ReportDatasetExecutionService executionService;
    @Mock
    private BusinessSnapshotService snapshotService;
    @Mock
    private BusinessSnapshotMapper snapshotMapper;

    private SubjectSelectionTokenService selectionTokenService;
    private PersonBusinessQueryService service;
    private String selectionToken;

    @BeforeEach
    void setUp() throws Exception {
        selectionTokenService = new SubjectSelectionTokenService(10);
        service = new PersonBusinessQueryService(
                selectionTokenService,
                personDirectoryService,
                new PersonSnapshotReuseService(
                        snapshotMatcher, snapshotMapper, new ObjectMapper(), Clock.systemUTC()
                ),
                executionService,
                snapshotService,
                new AttendanceReconciliationService()
        );
        selectionToken = selectionTokenService.issue(
                EMPLOYEE_NO, USER_ID, SESSION_ID, BusinessSubjectType.PERSON
        );
    }

    @Test
    void invalidSelectionTokenFailsClosedBeforeDirectoryLookup() {
        assertThatThrownBy(() -> service.query(command("invalid-token", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主体");

        verify(personDirectoryService, never()).search(any());
        verify(snapshotMatcher, never()).match(any());
    }

    @Test
    void currentDirectoryDenialFailsClosedBeforeBusinessDatasetAccess() {
        when(personDirectoryService.search(any())).thenReturn(SubjectDirectoryPage.denied());

        assertThatThrownBy(() -> service.query(command(selectionToken, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可查询");

        verify(snapshotMatcher, never()).match(any());
        verify(executionService, never()).execute(any());
    }

    @Test
    void inconsistentAttendanceDatasetDateRangesFailBeforeDirectoryLookup() {
        Command inconsistent = replacePlan(
                command(selectionToken, false),
                DatasetType.PUNCH,
                Map.of(
                        "moduleMarker", DatasetType.PUNCH.name(),
                        "employeeNo", "UNTRUSTED",
                        "startDate", "2026-07-01",
                        "endDate", "2026-07-31"
                ),
                "DAY"
        );

        assertThatThrownBy(() -> service.query(inconsistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间范围");

        verify(personDirectoryService, never()).search(any());
        verify(snapshotMatcher, never()).match(any());
        verify(executionService, never()).execute(any());
    }

    @Test
    void inconsistentAttendanceRequestedGrainFailsBeforeDirectoryLookup() {
        Command inconsistent = replacePlan(
                command(selectionToken, false),
                DatasetType.PUNCH,
                Map.of(
                        "moduleMarker", DatasetType.PUNCH.name(),
                        "employeeNo", "UNTRUSTED",
                        "startDate", "2026-08-03",
                        "endDate", "2026-08-03"
                ),
                "MONTH"
        );

        assertThatThrownBy(() -> service.query(inconsistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("粒度");

        verify(personDirectoryService, never()).search(any());
        verify(snapshotMatcher, never()).match(any());
        verify(executionService, never()).execute(any());
    }

    @Test
    void sixDatasetPlansKeepIndependentCodesAndInputsAndOverrideEmployeeNumber() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        service.query(command(selectionToken, false));

        ArgumentCaptor<DatasetExecutionRequest> captor =
                ArgumentCaptor.forClass(DatasetExecutionRequest.class);
        verify(executionService, org.mockito.Mockito.times(6)).execute(captor.capture());
        Map<String, DatasetExecutionRequest> byCode = new LinkedHashMap<>();
        captor.getAllValues().forEach(request -> byCode.put(request.datasetCode(), request));
        assertThat(byCode).hasSize(6);
        for (DatasetType type : DatasetType.values()) {
            DatasetExecutionRequest request = byCode.get(datasetCode(type));
            assertThat(request).isNotNull();
            assertThat(request.canonicalInput())
                    .containsEntry("moduleMarker", type.name())
                    .containsEntry("employeeNo", EMPLOYEE_NO)
                    .doesNotContainEntry("employeeNo", "UNTRUSTED");
            assertThat(request.toString()).doesNotContain(EMPLOYEE_NO);
        }
    }

    @Test
    void reimbursementOnlyExecutesOneDatasetAndKeepsTravelSummaryIncomplete() {
        prepareSuccessfulExecution();

        Result result = service.query(commandWithPlans(
                Set.of(DatasetType.REIMBURSEMENT), DatasetType.REIMBURSEMENT
        ));

        assertThat(result.modules()).extracting(PersonBusinessQueryService.ModuleResult::type)
                .containsExactly(DatasetType.REIMBURSEMENT);
        assertThat(result.travelSummary().tripCount().complete()).isFalse();
        assertThat(result.travelSummary().totalAmount().complete()).isFalse();
        assertThat(result.reimbursementSummary().paidAmount().complete()).isTrue();
        assertThat(result.attendance()).isEmpty();
        verify(executionService, org.mockito.Mockito.times(1)).execute(any());
    }

    @Test
    void travelOnlyExecutesOneDatasetAndKeepsReimbursementSummaryIncomplete() {
        prepareSuccessfulExecution();

        Result result = service.query(commandWithPlans(
                Set.of(DatasetType.TRAVEL), DatasetType.TRAVEL
        ));

        assertThat(result.modules()).extracting(PersonBusinessQueryService.ModuleResult::type)
                .containsExactly(DatasetType.TRAVEL);
        assertThat(result.travelSummary().tripCount().complete()).isTrue();
        assertThat(result.reimbursementSummary().paidAmount().complete()).isFalse();
        assertThat(result.attendance()).isEmpty();
        verify(executionService, org.mockito.Mockito.times(1)).execute(any());
    }

    @Test
    void attendanceQueryExecutesExactlyFiveDependencyDatasets() {
        prepareSuccessfulExecution();

        Result result = service.query(commandWithPlans(
                Set.of(DatasetType.PUNCH),
                DatasetType.TRAVEL,
                DatasetType.PUNCH,
                DatasetType.LEAVE,
                DatasetType.SCHEDULE,
                DatasetType.CALENDAR
        ));

        assertThat(result.modules()).extracting(PersonBusinessQueryService.ModuleResult::type)
                .containsExactly(
                        DatasetType.TRAVEL,
                        DatasetType.PUNCH,
                        DatasetType.LEAVE,
                        DatasetType.SCHEDULE,
                        DatasetType.CALENDAR
                );
        assertThat(result.reimbursementSummary().paidAmount().complete()).isFalse();
        assertThat(result.attendance()).hasSize(1);
        verify(executionService, org.mockito.Mockito.times(5)).execute(any());
    }

    @Test
    void punchWithoutAttendanceDependenciesIsRejectedBeforeDirectoryLookup() {
        Command invalid = commandWithPlans(Set.of(DatasetType.PUNCH), DatasetType.PUNCH);

        assertThatThrownBy(() -> service.query(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("考勤查询缺少依赖数据集");

        verify(personDirectoryService, never()).search(any());
    }

    @Test
    void planWithoutUserRequestedRootIsRejectedBeforeDirectoryLookup() {
        Command invalid = commandWithPlans(Set.of(), DatasetType.LEAVE);

        assertThatThrownBy(() -> service.query(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少用户请求");

        verify(personDirectoryService, never()).search(any());
    }

    @Test
    void nonRootDatasetCannotBeMarkedAsUserRequested() {
        Command invalid = commandWithPlans(Set.of(DatasetType.LEAVE), DatasetType.LEAVE);

        assertThatThrownBy(() -> service.query(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("根数据集不合法");

        verify(personDirectoryService, never()).search(any());
    }

    @Test
    void internalLeavePlanWithoutPunchRequestIsRejectedBeforeDirectoryLookup() {
        Command invalid = commandWithPlans(
                Set.of(DatasetType.TRAVEL), DatasetType.TRAVEL, DatasetType.LEAVE
        );

        assertThatThrownBy(() -> service.query(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无关内部依赖");

        verify(personDirectoryService, never()).search(any());
    }

    @Test
    void failedAttendanceDependencyKeepsItsModuleIncomplete() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            if (typeFor(request.datasetCode()) == DatasetType.LEAVE) {
                return outcome(
                        request, DatasetExecutionStatus.TIMEOUT, false,
                        emptyFactsFor(request.datasetCode())
                );
            }
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(commandWithPlans(
                Set.of(DatasetType.PUNCH),
                DatasetType.TRAVEL,
                DatasetType.PUNCH,
                DatasetType.LEAVE,
                DatasetType.SCHEDULE,
                DatasetType.CALENDAR
        ));

        assertThat(result.modules())
                .filteredOn(module -> module.type() == DatasetType.LEAVE)
                .singleElement()
                .satisfies(module -> {
                    assertThat(module.status())
                            .isEqualTo(PersonBusinessQueryService.ModuleStatus.TIMEOUT);
                    assertThat(module.complete()).isFalse();
                });
        assertThat(result.attendance()).singleElement().satisfies(day ->
                assertThat(day.determination()).isEqualTo("UNKNOWN")
        );
    }

    @Test
    void validSnapshotsAreReusedWithoutDatasetExecution() throws Exception {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenAnswer(invocation -> {
            BusinessSnapshotMatcher.MatchCommand match = invocation.getArgument(0);
            return new SnapshotMatchResult(
                    SnapshotMatchDecision.REUSE,
                    "snapshot-" + match.datasetCode(),
                    "查询条件完全一致"
            );
        });
        when(snapshotMapper.selectById(any())).thenAnswer(invocation -> {
            String snapshotId = invocation.getArgument(0);
            String datasetCode = snapshotId.substring("snapshot-".length());
            return reusableSnapshot(snapshotId, datasetCode, emptyFactsFor(datasetCode));
        });

        Result result = service.query(command(selectionToken, false));

        assertThat(result.modules()).allSatisfy(module -> {
            assertThat(module.status()).isEqualTo(PersonBusinessQueryService.ModuleStatus.REUSED);
            assertThat(module.snapshotId()).isEqualTo("snapshot-" + module.datasetCode());
            assertThat(module.fieldPolicyChecksum()).isEqualTo("a".repeat(64));
        });
        verify(executionService, never()).execute(any());
        verify(snapshotService, never()).create(any());
    }

    @Test
    void snapshotThatFailsSecondOwnershipCheckIsRequeried() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenAnswer(invocation -> {
            BusinessSnapshotMatcher.MatchCommand match = invocation.getArgument(0);
            return new SnapshotMatchResult(
                    SnapshotMatchDecision.REUSE,
                    "snapshot-" + match.datasetCode(),
                    "查询条件完全一致"
            );
        });
        when(snapshotMapper.selectById(any())).thenAnswer(invocation -> {
            BusinessSnapshot invalid = reusableSnapshot(
                    invocation.getArgument(0),
                    invocation.<String>getArgument(0).substring("snapshot-".length()),
                    Map.of()
            );
            invalid.setUserId("another-user");
            return invalid;
        });
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.modules()).allSatisfy(module -> {
            assertThat(module.status()).isEqualTo(PersonBusinessQueryService.ModuleStatus.SUCCESS);
            assertThat(module.snapshotId()).isEqualTo("fresh-" + module.datasetCode());
            assertThat(module.fieldPolicyChecksum()).isEqualTo("a".repeat(64));
        });
        verify(executionService, org.mockito.Mockito.times(6)).execute(any());
        ArgumentCaptor<BusinessSnapshotService.CreateCommand> createCaptor =
                ArgumentCaptor.forClass(BusinessSnapshotService.CreateCommand.class);
        verify(snapshotService, org.mockito.Mockito.times(6)).create(createCaptor.capture());
        assertThat(createCaptor.getAllValues()).allSatisfy(create ->
                assertThat(create.toString()).doesNotContain(EMPLOYEE_NO, "UNTRUSTED")
        );
    }

    @Test
    void snapshotWithMismatchedQueryHashIsRequeried() {
        assertRejectedSnapshotsAreRequeried(snapshot ->
                snapshot.setQueryHash("f".repeat(64))
        );
    }

    @Test
    void snapshotWithOversizedFactsIsRequeried() {
        assertRejectedSnapshotsAreRequeried(snapshot ->
                snapshot.setFactsJson(
                        "{\"person\":{\"calculation\":{\""
                                + factCode(typeFor(snapshot.getDatasetCode()))
                                + "\":[]}},\"padding\":\""
                                + "测".repeat(90_000)
                                + "\"}"
                )
        );
    }

    @Test
    void missingSnapshotsExecuteEachDatasetAndCreateSafeSnapshots() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.modules()).allSatisfy(module -> {
            assertThat(module.status()).isEqualTo(PersonBusinessQueryService.ModuleStatus.SUCCESS);
            assertThat(module.snapshotId()).isEqualTo("fresh-" + module.datasetCode());
            assertThat(module.fieldPolicyChecksum()).isEqualTo("a".repeat(64));
        });
        verify(executionService, org.mockito.Mockito.times(6)).execute(any());
        verify(snapshotService, org.mockito.Mockito.times(6)).create(any());
    }

    @Test
    void travelAndReimbursementFactsAreAggregatedWithBigDecimalExactly() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            Map<String, Object> facts = switch (typeFor(request.datasetCode())) {
                case TRAVEL -> calculation(PersonBusinessQueryService.TRAVEL_RECORDS, List.of(
                        Map.of(
                                "startAt", "2026-08-03T09:00:00",
                                "endAt", "2026-08-03T18:00:00",
                                "approvalStatus", "APPROVED",
                                "amount", new BigDecimal("100.10")
                        ),
                        Map.of(
                                "startAt", "2026-08-04T09:00:00",
                                "endAt", "2026-08-04T18:00:00",
                                "approvalStatus", "APPROVED",
                                "amount", new BigDecimal("20.90")
                        )
                ));
                case REIMBURSEMENT -> calculation(
                        PersonBusinessQueryService.REIMBURSEMENT_RECORDS,
                        List.of(
                                Map.of(
                                        "requestedAmount", new BigDecimal("80.00"),
                                        "approvedAmount", new BigDecimal("70.00"),
                                        "paidAmount", new BigDecimal("60.00")
                                ),
                                Map.of(
                                        "requestedAmount", new BigDecimal("20.25"),
                                        "approvedAmount", new BigDecimal("18.25"),
                                        "paidAmount", new BigDecimal("18.25")
                                )
                        )
                );
                default -> emptyFactsFor(request.datasetCode());
            };
            return success(request, facts);
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.travelSummary().tripCount().complete()).isTrue();
        assertThat(result.travelSummary().tripCount().value()).isEqualTo(2);
        assertThat(result.travelSummary().totalAmount().value())
                .isEqualByComparingTo("121.00");
        assertThat(result.reimbursementSummary().requestedAmount().value())
                .isEqualByComparingTo("100.25");
        assertThat(result.reimbursementSummary().approvedAmount().value())
                .isEqualByComparingTo("88.25");
        assertThat(result.reimbursementSummary().paidAmount().value())
                .isEqualByComparingTo("78.25");
    }

    @Test
    void travelSummaryCountsAndSumsOnlyApprovedRecords() {
        Result result = queryWithTravelRecords(List.of(
                travelRecord("APPROVED", new BigDecimal("100.00")),
                travelRecord("CANCELLED", new BigDecimal("20.00")),
                travelRecord("REJECTED", new BigDecimal("30.00")),
                travelRecord("PENDING", new BigDecimal("40.00"))
        ));

        assertThat(result.travelSummary().tripCount().value()).isEqualTo(1);
        assertThat(result.travelSummary().totalAmount().value())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void missingTravelApprovalStatusMakesCountAndAmountIncomplete() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("startAt", "2026-08-03T09:00:00");
        record.put("endAt", "2026-08-03T18:00:00");
        record.put("amount", new BigDecimal("100.00"));

        Result result = queryWithTravelRecords(List.of(Map.copyOf(record)));

        assertThat(result.travelSummary().tripCount().complete()).isFalse();
        assertThat(result.travelSummary().tripCount().value()).isNull();
        assertThat(result.travelSummary().totalAmount().complete()).isFalse();
        assertThat(result.travelSummary().totalAmount().value()).isNull();
    }

    @Test
    void nonStringTravelApprovalStatusMakesCountAndAmountIncomplete() {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("startAt", "2026-08-03T09:00:00");
        record.put("endAt", "2026-08-03T18:00:00");
        record.put("approvalStatus", 1);
        record.put("amount", new BigDecimal("100.00"));

        Result result = queryWithTravelRecords(List.of(Map.copyOf(record)));

        assertThat(result.travelSummary().tripCount().complete()).isFalse();
        assertThat(result.travelSummary().totalAmount().complete()).isFalse();
    }

    @Test
    void invalidApprovedTravelAmountKeepsCountCompleteButAmountIncomplete() {
        Map<String, Object> missingAmount = new LinkedHashMap<>(
                travelRecord("APPROVED", new BigDecimal("1.00"))
        );
        missingAmount.remove("amount");

        Result result = queryWithTravelRecords(List.of(
                Map.copyOf(missingAmount),
                travelRecord("APPROVED", "not-a-decimal")
        ));

        assertThat(result.travelSummary().tripCount().complete()).isTrue();
        assertThat(result.travelSummary().tripCount().value()).isEqualTo(2);
        assertThat(result.travelSummary().totalAmount().complete()).isFalse();
        assertThat(result.travelSummary().totalAmount().value()).isNull();
    }

    @Test
    void refreshForcesAllDatasetsToExecuteEvenIfMatcherOffersReuse() {
        authorizeSelectedPerson();
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        service.query(command(selectionToken, true));

        verify(snapshotMatcher, never()).match(any());
        verify(snapshotMapper, never()).selectById(any());
        verify(executionService, org.mockito.Mockito.times(6)).execute(any());
    }

    @Test
    void deniedReimbursementDoesNotInventZeroAmounts() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            if (typeFor(request.datasetCode()) == DatasetType.REIMBURSEMENT) {
                return outcome(request, DatasetExecutionStatus.DENIED, false, Map.of());
            }
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.reimbursementSummary().requestedAmount().complete()).isFalse();
        assertThat(result.reimbursementSummary().requestedAmount().value()).isNull();
        assertThat(result.reimbursementSummary().approvedAmount().value()).isNull();
        assertThat(result.reimbursementSummary().paidAmount().value()).isNull();
        assertThat(result.modules()).anyMatch(module ->
                module.type() == DatasetType.REIMBURSEMENT
                        && module.status() == PersonBusinessQueryService.ModuleStatus.DENIED
                        && module.snapshotId() == null
                        && module.fieldPolicyChecksum() == null
        );
        verify(snapshotService, org.mockito.Mockito.times(5)).create(any());
    }

    @Test
    void failedAndTimeoutModulesDoNotExposeSnapshotReferences() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(new SnapshotMatchResult(
                SnapshotMatchDecision.REQUERY, null,
                BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
        ));
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            DatasetType type = typeFor(request.datasetCode());
            if (type == DatasetType.TRAVEL) {
                return outcome(request, DatasetExecutionStatus.FAILED, false, Map.of());
            }
            if (type == DatasetType.PUNCH) {
                return outcome(request, DatasetExecutionStatus.TIMEOUT, false, Map.of());
            }
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.modules())
                .filteredOn(module -> module.status() == PersonBusinessQueryService.ModuleStatus.FAILED
                        || module.status() == PersonBusinessQueryService.ModuleStatus.TIMEOUT)
                .hasSize(2)
                .allSatisfy(module -> {
                    assertThat(module.snapshotId()).isNull();
                    assertThat(module.fieldPolicyChecksum()).isNull();
                });
    }

    @Test
    void emptyModuleKeepsCreatedSnapshotReference() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(new SnapshotMatchResult(
                SnapshotMatchDecision.REQUERY, null,
                BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
        ));
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            if (typeFor(request.datasetCode()) == DatasetType.TRAVEL) {
                return outcome(
                        request, DatasetExecutionStatus.EMPTY, true,
                        emptyFactsFor(request.datasetCode())
                );
            }
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.modules())
                .filteredOn(module -> module.type() == DatasetType.TRAVEL)
                .singleElement()
                .satisfies(module -> {
                    assertThat(module.status()).isEqualTo(
                            PersonBusinessQueryService.ModuleStatus.EMPTY
                    );
                    assertThat(module.snapshotId()).isEqualTo("fresh-PERSON_TRAVEL");
                    assertThat(module.fieldPolicyChecksum()).isEqualTo("a".repeat(64));
                });
    }

    @Test
    void moduleResultToStringDoesNotExposeSnapshotIdOrPolicyChecksum() {
        PersonBusinessQueryService.ModuleResult module =
                new PersonBusinessQueryService.ModuleResult(
                        DatasetType.TRAVEL,
                        datasetCode(DatasetType.TRAVEL),
                        PersonBusinessQueryService.ModuleStatus.SUCCESS,
                        true,
                        "snapshot-sensitive",
                        "a".repeat(64)
                );

        assertThat(module.toString())
                .contains("snapshotPresent=true", "fieldPolicyPresent=true")
                .doesNotContain("snapshot-sensitive", "a".repeat(64), EMPLOYEE_NO);
    }

    @Test
    void moduleResultRejectsMissingIdentityAndInvalidDatasetCode() {
        assertThatThrownBy(() -> new PersonBusinessQueryService.ModuleResult(
                null, "PERSON_TRAVEL", PersonBusinessQueryService.ModuleStatus.SUCCESS,
                true, "snapshot-1", "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonBusinessQueryService.ModuleResult(
                DatasetType.TRAVEL, "PERSON_TRAVEL", null,
                true, "snapshot-1", "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PersonBusinessQueryService.ModuleResult(
                DatasetType.TRAVEL, "person-travel", PersonBusinessQueryService.ModuleStatus.SUCCESS,
                true, "snapshot-1", "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moduleResultAccepts128CharacterDatasetCodeAndRejects129Characters() {
        String maximumDatasetCode = "A" + "_".repeat(127);
        String oversizedDatasetCode = "A" + "_".repeat(128);

        PersonBusinessQueryService.ModuleResult accepted =
                new PersonBusinessQueryService.ModuleResult(
                        DatasetType.TRAVEL,
                        maximumDatasetCode,
                        PersonBusinessQueryService.ModuleStatus.SUCCESS,
                        true,
                        "snapshot-1",
                        "a".repeat(64)
                );

        assertThat(accepted.datasetCode()).hasSize(128);
        assertThatThrownBy(() -> new PersonBusinessQueryService.ModuleResult(
                DatasetType.TRAVEL,
                oversizedDatasetCode,
                PersonBusinessQueryService.ModuleStatus.SUCCESS,
                true,
                "snapshot-1",
                "a".repeat(64)
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moduleResultEnforcesStatusAndSnapshotReferenceInvariant() {
        for (PersonBusinessQueryService.ModuleStatus status : List.of(
                PersonBusinessQueryService.ModuleStatus.SUCCESS,
                PersonBusinessQueryService.ModuleStatus.EMPTY,
                PersonBusinessQueryService.ModuleStatus.REUSED)) {
            assertThatThrownBy(() -> new PersonBusinessQueryService.ModuleResult(
                    DatasetType.TRAVEL, "PERSON_TRAVEL", status,
                    true, null, "a".repeat(64)
            )).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PersonBusinessQueryService.ModuleResult(
                    DatasetType.TRAVEL, "PERSON_TRAVEL", status,
                    true, "snapshot-1", "invalid-checksum"
            )).isInstanceOf(IllegalArgumentException.class);
        }
        for (PersonBusinessQueryService.ModuleStatus status : List.of(
                PersonBusinessQueryService.ModuleStatus.DENIED,
                PersonBusinessQueryService.ModuleStatus.FAILED,
                PersonBusinessQueryService.ModuleStatus.TIMEOUT)) {
            assertThatThrownBy(() -> new PersonBusinessQueryService.ModuleResult(
                    DatasetType.TRAVEL, "PERSON_TRAVEL", status,
                    false, "snapshot-1", "a".repeat(64)
            )).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void executionExceptionWritesSafeStructuredWarning() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            if (typeFor(request.datasetCode()) == DatasetType.TRAVEL) {
                throw new IllegalStateException(
                        "Bearer secret UNTRUSTED " + EMPLOYEE_NO + " tenant-secret"
                );
            }
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        List<String> messages = captureServiceWarnings(() ->
                service.query(command(selectionToken, false))
        );

        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains(
                        "category=EXECUTION",
                        "datasetCode=PERSON_TRAVEL",
                        "exceptionType=IllegalStateException"
                )
                .doesNotContain(
                        "Bearer secret", "UNTRUSTED", EMPLOYEE_NO, "tenant-secret"
                ));
    }

    @Test
    void snapshotCreationExceptionWritesSafeStructuredWarning() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation -> {
            BusinessSnapshotService.CreateCommand create = invocation.getArgument(0);
            if (typeFor(create.datasetCode()) == DatasetType.REIMBURSEMENT) {
                throw new IllegalArgumentException(
                        "Bearer secret UNTRUSTED " + EMPLOYEE_NO + " tenant-secret"
                );
            }
            return freshSnapshot(create);
        });

        List<String> messages = captureServiceWarnings(() ->
                service.query(command(selectionToken, false))
        );

        assertThat(messages).anySatisfy(message -> assertThat(message)
                .contains(
                        "category=SNAPSHOT_CREATE",
                        "datasetCode=PERSON_REIMBURSEMENT",
                        "exceptionType=IllegalArgumentException"
                )
                .doesNotContain(
                        "Bearer secret", "UNTRUSTED", EMPLOYEE_NO, "tenant-secret"
                ));
    }

    @Test
    void missingScheduleAndCalendarFactsProduceUnknownAttendanceDay() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.attendance()).singleElement().satisfies(day -> {
            assertThat(day.date()).hasToString("2026-08-03");
            assertThat(day.determination()).isEqualTo("UNKNOWN");
        });
    }

    @Test
    void calendarScheduleAndPunchFactsProduceDeterministicAttendance() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            Map<String, Object> facts = switch (typeFor(request.datasetCode())) {
                case CALENDAR -> calculation(
                        PersonBusinessQueryService.CALENDAR_RECORDS,
                        List.of(Map.of("date", "2026-08-03", "workingDay", true))
                );
                case SCHEDULE -> calculation(
                        PersonBusinessQueryService.SCHEDULE_RECORDS,
                        List.of(Map.of(
                                "date", "2026-08-03",
                                "startTime", "09:00",
                                "endTime", "18:00"
                        ))
                );
                case PUNCH -> calculation(
                        PersonBusinessQueryService.PUNCH_RECORDS,
                        List.of(
                                Map.of("time", "2026-08-03T08:55:00"),
                                Map.of("time", "2026-08-03T18:05:00")
                        )
                );
                default -> emptyFactsFor(request.datasetCode());
            };
            return success(request, facts);
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.attendance()).singleElement().satisfies(day -> {
            assertThat(day.date()).hasToString("2026-08-03");
            assertThat(day.rawPunchStatus()).isEqualTo("COMPLETE");
            assertThat(day.determination()).isEqualTo("NORMAL");
        });
    }

    @Test
    void resultExposesOnlyDerivedSummariesAttendanceAndModuleStates() throws Exception {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        String modelJson = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(service.query(command(selectionToken, false)));

        assertThat(modelJson)
                .contains("travelSummary", "reimbursementSummary", "attendance", "modules")
                .contains("snapshotId", "fieldPolicyChecksum")
                .doesNotContain(
                        EMPLOYEE_NO,
                        "Bearer secret",
                        "tenant-secret",
                        "calculation",
                        PersonBusinessQueryService.TRAVEL_RECORDS
                );
    }

    @Test
    void businessFactValuesAreNotExposedByToString() {
        PersonBusinessQueryService.Metric<BigDecimal> amount =
                PersonBusinessQueryService.Metric.complete(new BigDecimal("98765.43"));
        AttendanceDayResult attendance = new AttendanceDayResult(
                LocalDate.of(2026, 8, 3),
                "COMPLETE",
                "LEAVE",
                "LEAVE_EXEMPT",
                List.of(PersonBusinessQueryService.LEAVE_RECORDS)
        );
        Result result = new Result(
                new PersonBusinessQueryService.TravelSummary(
                        PersonBusinessQueryService.Metric.complete(7), amount
                ),
                new PersonBusinessQueryService.ReimbursementSummary(amount, amount, amount),
                List.of(attendance),
                List.of()
        );

        assertThat(amount.toString()).doesNotContain("98765.43");
        assertThat(attendance.toString()).doesNotContain(
                "2026-08-03", "COMPLETE", "LEAVE", PersonBusinessQueryService.LEAVE_RECORDS
        );
        assertThat(result.toString()).doesNotContain(
                "98765.43", "2026-08-03", "COMPLETE", "LEAVE",
                PersonBusinessQueryService.LEAVE_RECORDS
        );
    }

    private void authorizeSelectedPerson() {
        AuthorizedSubjectCandidate candidate = new AuthorizedSubjectCandidate(
                BusinessSubjectType.PERSON,
                EMPLOYEE_NO,
                "测试员工",
                "E1***01",
                "研发部",
                null,
                null
        );
        when(personDirectoryService.search(any())).thenReturn(
                new SubjectDirectoryPage(true, List.of(candidate), 1, 1, 1, false)
        );
    }

    private void prepareSuccessfulExecution() {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );
    }

    private Command commandWithPlans(
            Set<DatasetType> requestedTypes,
            DatasetType... executionTypes) {
        Set<DatasetType> included = Set.of(executionTypes);
        Command source = command(selectionToken, false);
        return new Command(
                source.agentRunId(), source.userId(), source.sessionId(),
                source.authorization(), source.secureContext(), source.selectionToken(),
                source.refreshRequested(), source.plans().stream()
                        .filter(plan -> included.contains(plan.type()))
                        .map(plan -> new DatasetPlan(
                                plan.type(), plan.datasetCode(), plan.canonicalInput(),
                                plan.requestedGrain(), plan.requiredFactCodes(),
                                requestedTypes.contains(plan.type())
                        ))
                        .toList()
        );
    }

    private Command command(String token, boolean refreshRequested) {
        List<DatasetPlan> plans = new ArrayList<>();
        for (DatasetType type : DatasetType.values()) {
            plans.add(new DatasetPlan(
                    type,
                    datasetCode(type),
                    Map.of(
                            "moduleMarker", type.name(),
                            "employeeNo", "UNTRUSTED",
                            "startDate", "2026-08-03",
                            "endDate", "2026-08-03"
                    ),
                    "DAY",
                    Set.of(factCode(type)),
                    type == DatasetType.TRAVEL
                            || type == DatasetType.PUNCH
                            || type == DatasetType.REIMBURSEMENT
            ));
        }
        return new Command(
                "agent-run-1",
                USER_ID,
                SESSION_ID,
                "Bearer secret",
                Map.of("tenant", "tenant-secret"),
                token,
                refreshRequested,
                plans
        );
    }

    private List<String> captureServiceWarnings(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PersonBusinessQueryService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private Command replacePlan(
            Command source,
            DatasetType type,
            Map<String, Object> canonicalInput,
            String requestedGrain) {
        List<DatasetPlan> plans = source.plans().stream()
                .map(plan -> plan.type() == type
                        ? new DatasetPlan(
                        type,
                        plan.datasetCode(),
                        canonicalInput,
                        requestedGrain,
                        plan.requiredFactCodes(),
                        plan.userRequested()
                )
                        : plan)
                .toList();
        return new Command(
                source.agentRunId(), source.userId(), source.sessionId(),
                source.authorization(), source.secureContext(), source.selectionToken(),
                source.refreshRequested(), plans
        );
    }

    private DatasetExecutionResult success(
            DatasetExecutionRequest request,
            Map<String, Object> safeFacts) {
        return outcome(request, DatasetExecutionStatus.SUCCESS, true, safeFacts);
    }

    private DatasetExecutionResult outcome(
            DatasetExecutionRequest request,
            DatasetExecutionStatus status,
            boolean complete,
            Map<String, Object> safeFacts) {
        DatasetExecutionSource source = new DatasetExecutionSource(
                request.userId(),
                request.sessionId(),
                request.subjectType(),
                request.subjectId(),
                request.datasetCode(),
                "a".repeat(64),
                "query-" + request.datasetCode(),
                1L,
                "b".repeat(64),
                "c".repeat(64)
        );
        return new DatasetExecutionResult(
                source,
                status,
                complete,
                safeFacts,
                "run-" + request.datasetCode(),
                null,
                null,
                "完成",
                "d".repeat(64)
        );
    }

    private BusinessSnapshot freshSnapshot(BusinessSnapshotService.CreateCommand command)
            throws Exception {
        DatasetExecutionResult result = command.items().get(0).result();
        return reusableSnapshot(
                "fresh-" + command.datasetCode(),
                command.datasetCode(),
                result.safeFacts()
        );
    }

    private BusinessSnapshot reusableSnapshot(
            String snapshotId,
            String datasetCode,
            Map<String, Object> facts) throws Exception {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setUserId(USER_ID);
        snapshot.setSessionId(SESSION_ID);
        snapshot.setSubjectType(BusinessSubjectType.PERSON.name());
        snapshot.setSubjectId(EMPLOYEE_NO);
        snapshot.setDatasetCode(datasetCode);
        snapshot.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(Map.of(
                        "moduleMarker", typeFor(datasetCode).name(),
                        "employeeNo", EMPLOYEE_NO,
                        "startDate", "2026-08-03",
                        "endDate", "2026-08-03"
                ))
        ));
        snapshot.setStatus("COMPLETE");
        snapshot.setDataComplete(true);
        snapshot.setFieldPolicyChecksum("a".repeat(64));
        snapshot.setExpiresAt(LocalDateTime.now().plusHours(1));
        snapshot.setFactsJson(new ObjectMapper().writeValueAsString(
                Map.of("person", facts)
        ));
        return snapshot;
    }

    private void assertRejectedSnapshotsAreRequeried(
            java.util.function.Consumer<BusinessSnapshot> mutation) {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenAnswer(invocation -> {
            BusinessSnapshotMatcher.MatchCommand match = invocation.getArgument(0);
            return new SnapshotMatchResult(
                    SnapshotMatchDecision.REUSE,
                    "snapshot-" + match.datasetCode(),
                    "查询条件完全一致"
            );
        });
        when(snapshotMapper.selectById(any())).thenAnswer(invocation -> {
            String snapshotId = invocation.getArgument(0);
            String datasetCode = snapshotId.substring("snapshot-".length());
            BusinessSnapshot snapshot = reusableSnapshot(
                    snapshotId, datasetCode, emptyFactsFor(datasetCode)
            );
            mutation.accept(snapshot);
            return snapshot;
        });
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return success(request, emptyFactsFor(request.datasetCode()));
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );

        Result result = service.query(command(selectionToken, false));

        assertThat(result.modules()).allMatch(module ->
                module.status() == PersonBusinessQueryService.ModuleStatus.SUCCESS
        );
        verify(executionService, org.mockito.Mockito.times(6)).execute(any());
        verify(snapshotService, org.mockito.Mockito.times(6)).create(any());
    }

    private Map<String, Object> emptyFactsFor(String datasetCode) {
        return calculation(factCode(typeFor(datasetCode)), List.of());
    }

    private Map<String, Object> calculation(String code, Object value) {
        return Map.of(
                "calculation", Map.of(code, value),
                "display", Map.of(),
                "export", Map.of(),
                "model", Map.of()
        );
    }

    private Map<String, Object> travelRecord(String status, Object amount) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("startAt", "2026-08-03T09:00:00");
        record.put("endAt", "2026-08-03T18:00:00");
        record.put("approvalStatus", status);
        record.put("amount", amount);
        return Map.copyOf(record);
    }

    private Result queryWithTravelRecords(List<Map<String, Object>> records) {
        authorizeSelectedPerson();
        when(snapshotMatcher.match(any())).thenReturn(
                new SnapshotMatchResult(
                        SnapshotMatchDecision.REQUERY, null,
                        BusinessSnapshotMatcher.GENERIC_REQUERY_REASON
                )
        );
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            Map<String, Object> facts = typeFor(request.datasetCode()) == DatasetType.TRAVEL
                    ? calculation(PersonBusinessQueryService.TRAVEL_RECORDS, records)
                    : emptyFactsFor(request.datasetCode());
            return success(request, facts);
        });
        when(snapshotService.create(any())).thenAnswer(invocation ->
                freshSnapshot(invocation.getArgument(0))
        );
        return service.query(command(selectionToken, false));
    }

    private String datasetCode(DatasetType type) {
        return "PERSON_" + type.name();
    }

    private DatasetType typeFor(String datasetCode) {
        return DatasetType.valueOf(datasetCode.substring("PERSON_".length()));
    }

    private String factCode(DatasetType type) {
        return switch (type) {
            case TRAVEL -> PersonBusinessQueryService.TRAVEL_RECORDS;
            case PUNCH -> PersonBusinessQueryService.PUNCH_RECORDS;
            case LEAVE -> PersonBusinessQueryService.LEAVE_RECORDS;
            case SCHEDULE -> PersonBusinessQueryService.SCHEDULE_RECORDS;
            case CALENDAR -> PersonBusinessQueryService.CALENDAR_RECORDS;
            case REIMBURSEMENT -> PersonBusinessQueryService.REIMBURSEMENT_RECORDS;
        };
    }
}
