package org.example.ai.agent.business;

import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.PanoramaDatasetExecutor;
import org.example.ai.agent.business.panorama.ProjectIssueRuleService;
import org.example.ai.agent.business.panorama.ProjectPanoramaExecutionService;
import org.example.ai.agent.business.panorama.ProjectPanoramaProfileService;
import org.example.ai.agent.business.panorama.ProjectPanoramaSnapshotService;
import org.example.ai.agent.business.panorama.ProjectSubjectAuthorizationService;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.person.AttendanceReconciliationService;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.person.PersonSnapshotReuseService;
import org.example.ai.agent.business.person.ProjectRecordAssociationService;
import org.example.ai.agent.business.report.BusinessAssistantReportService;
import org.example.ai.agent.business.report.CompositeReportTaskService;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.snapshot.BusinessSnapshotMatcher;
import org.example.ai.agent.business.snapshot.BusinessSnapshotReferenceValidationService;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.subject.AuthorizedPersonDirectoryService;
import org.example.ai.agent.business.subject.DepartmentDirectoryService;
import org.example.ai.agent.business.subject.ProjectDirectoryService;
import org.example.ai.agent.business.subject.SubjectResolutionService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.EMPLOYEE_NO;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.PROJECT_CODE;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.SESSION_ID;
import static org.example.ai.agent.business.BusinessAssistantAcceptanceFixture.USER_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 四条已确认用户故事的纵向验收门禁：主体定位、项目全景、人员确定性核算和 PDF 计划。
 * 端到端流式协议继续与 {@link BusinessAssistantServiceTest} 联合运行，避免复制其大型 Fixture。
 */
class BusinessAssistantAcceptanceTest {

    private static final String POLICY_CHECKSUM = "a".repeat(64);

    @Test
    void myProjectsReturnsPagedAuthorizedCandidatesBeforeAnyBusinessModuleCanRun() {
        ProjectDirectoryService projects = mock(ProjectDirectoryService.class);
        AuthorizedPersonDirectoryService people = mock(AuthorizedPersonDirectoryService.class);
        DepartmentDirectoryService departments = mock(DepartmentDirectoryService.class);
        SubjectResolutionService service = new SubjectResolutionService(
                projects, people, departments, new SubjectSelectionTokenService(10)
        );
        when(projects.search(any())).thenReturn(new SubjectDirectoryPage(
                true,
                List.of(project("project-1", PROJECT_CODE), project("project-2", "XXXT2674050")),
                1, 2, 6, true
        ));

        var result = service.resolve(new SubjectResolutionRequest(
                "run-1", USER_ID, SESSION_ID, "Bearer current-user", Map.of(),
                BusinessSubjectType.PROJECT, null, null, null, null,
                LocalDate.now().getYear(), true, null, 1, 2
        ));

        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.resolvedSubject()).isNull();
        assertThat(result.candidates()).extracting(candidate -> candidate.projectCode())
                .containsExactly(PROJECT_CODE, "XXXT2674050");
        assertThat(result.totalCount()).isEqualTo(6);
        assertThat(result.hasNext()).isTrue();
        verify(people, never()).search(any());
        verify(departments, never()).search(any());
    }

    @Test
    void exactProjectPanoramaKeepsDeterministicModuleStatesAndDisclosesPartialResult() {
        ProjectSubjectAuthorizationService authorization = mock(ProjectSubjectAuthorizationService.class);
        ProjectPanoramaProfileService profiles = mock(ProjectPanoramaProfileService.class);
        PanoramaDatasetExecutor executor = mock(PanoramaDatasetExecutor.class);
        BusinessSnapshotService snapshots = mock(BusinessSnapshotService.class);
        ProjectIssueRuleService rules = mock(ProjectIssueRuleService.class);
        ProjectPanoramaSnapshotService aggregateSnapshots = mock(ProjectPanoramaSnapshotService.class);
        when(authorization.authorize(any())).thenReturn(
                new AuthorizedProjectSubject("project-1", PROJECT_CODE, "DELIVERY", "示例项目")
        );
        when(profiles.resolve("DELIVERY")).thenReturn(new ProjectPanoramaPlan(
                1L, "DELIVERY", "项目全景", POLICY_CHECKSUM,
                List.of(
                        new ProjectPanoramaPlan.Module("CONTRACT", true, 10, 30_000),
                        new ProjectPanoramaPlan.Module("CASH_FLOW", false, 20, 30_000)
                )
        ));
        when(executor.execute(any(), anyInt()))
                .thenAnswer(invocation -> new PanoramaDatasetExecutor.Result(
                        DatasetExecutionStatus.SUCCESS,
                        execution(invocation.<DatasetExecutionRequest>getArgument(0),
                                DatasetExecutionStatus.SUCCESS, true,
                                channels("contractAmount", new BigDecimal("8600.00")))
                ))
                .thenReturn(new PanoramaDatasetExecutor.Result(DatasetExecutionStatus.DENIED, null));
        when(snapshots.create(any())).thenAnswer(invocation -> snapshot(
                "snapshot-contract", invocation.<BusinessSnapshotService.CreateCommand>getArgument(0)
                        .datasetCode()
        ));
        when(rules.loadRules(1L)).thenReturn(new ProjectIssueRuleService.RuleSet(1L, List.of()));
        when(rules.evaluate(any(), any())).thenReturn(List.of());
        when(aggregateSnapshots.create(any())).thenReturn(
                new ProjectPanoramaSnapshotService.SnapshotReference(
                        "panorama-1", "PARTIAL_SUCCESS", LocalDateTime.now().plusHours(1)
                )
        );
        ProjectPanoramaExecutionService service = new ProjectPanoramaExecutionService(
                authorization, profiles, executor, snapshots, rules, aggregateSnapshots, 8, 60_000
        );

        ProjectPanoramaResult result = service.execute(new ProjectPanoramaCommand(
                "run-1", USER_ID, SESSION_ID, "Bearer current-user", Map.of(),
                "project-token", Map.of("projectCode", PROJECT_CODE)
        ), ignored -> { });

        assertThat(result.state()).isEqualTo(PanoramaExecutionState.PARTIAL_SUCCESS);
        assertThat(result.requiredComplete()).isTrue();
        assertThat(result.allModulesComplete()).isFalse();
        assertThat(result.modules()).extracting(ProjectPanoramaResult.ModuleResult::status)
                .containsExactly(DatasetExecutionStatus.SUCCESS, DatasetExecutionStatus.DENIED);
        ArgumentCaptor<BusinessSnapshotService.CreateCommand> created =
                ArgumentCaptor.forClass(BusinessSnapshotService.CreateCommand.class);
        verify(snapshots).create(created.capture());
        assertThat(created.getValue().items()).singleElement()
                .extracting(BusinessSnapshotService.ItemCommand::associationType)
                .isEqualTo(AssociationType.DIRECT);
    }

    @Test
    void sixTripsAmountsAttendanceExemptionsAndReimbursementsComeFromRulesAndSupportReuseAndRefresh() {
        AuthorizedPersonDirectoryService directory = mock(AuthorizedPersonDirectoryService.class);
        PersonSnapshotReuseService reuse = mock(PersonSnapshotReuseService.class);
        ReportDatasetExecutionService execution = mock(ReportDatasetExecutionService.class);
        BusinessSnapshotService snapshots = mock(BusinessSnapshotService.class);
        SubjectSelectionTokenService tokens = new SubjectSelectionTokenService(10);
        String token = tokens.issue(EMPLOYEE_NO, USER_ID, SESSION_ID, BusinessSubjectType.PERSON);
        when(directory.search(any())).thenReturn(new SubjectDirectoryPage(
                true, List.of(person()), 1, 1, 1, false
        ));
        when(reuse.reuse(any())).thenAnswer(invocation -> {
            BusinessSnapshotMatcher.MatchCommand command = invocation.getArgument(0);
            return Optional.of(new PersonSnapshotReuseService.ReuseResult(
                    "snapshot-" + command.datasetCode(), POLICY_CHECKSUM, true,
                    calculationFor(command.datasetCode())
            ));
        });
        when(execution.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return execution(request, DatasetExecutionStatus.SUCCESS, true,
                    safeFacts(request.datasetCode()));
        });
        when(snapshots.create(any())).thenAnswer(invocation -> {
            BusinessSnapshotService.CreateCommand command = invocation.getArgument(0);
            return snapshot("fresh-" + command.datasetCode(), command.datasetCode());
        });
        PersonBusinessQueryService service = new PersonBusinessQueryService(
                tokens, directory, reuse, execution, snapshots, new AttendanceReconciliationService()
        );

        PersonBusinessQueryService.Result reused = service.query(personCommand(token, false));
        PersonBusinessQueryService.Result refreshed = service.query(personCommand(token, true));

        assertPersonStory(reused);
        assertPersonStory(refreshed);
        assertThat(reused.modules()).extracting(PersonBusinessQueryService.ModuleResult::status)
                .containsOnly(PersonBusinessQueryService.ModuleStatus.REUSED);
        assertThat(refreshed.modules()).extracting(PersonBusinessQueryService.ModuleResult::status)
                .containsOnly(PersonBusinessQueryService.ModuleStatus.SUCCESS);
        verify(reuse, org.mockito.Mockito.times(6)).reuse(any());
        verify(execution, org.mockito.Mockito.times(6)).execute(any());
    }

    @Test
    void personProjectPeriodPdfKeepsContextOutOfTotalsAndDisclosesIncompleteSection() {
        ProjectRecordAssociationService association = new ProjectRecordAssociationService();
        ProjectRecordAssociationService.ProjectAssociationResult associated = association.associate(
                new ProjectRecordAssociationService.ProjectIdentity(PROJECT_CODE, "project-1"),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                List.of(
                        record("direct-travel", PROJECT_CODE, null, "100.00", "0"),
                        record("context-reimbursement", null, LocalDate.of(2026, 1, 1), "0", "900.00")
                )
        );
        assertThat(associated.directRecords()).singleElement()
                .extracting(ProjectRecordAssociationService.AssociatedRecord::type)
                .isEqualTo(AssociationType.DIRECT);
        assertThat(associated.contextRecords()).singleElement()
                .extracting(ProjectRecordAssociationService.AssociatedRecord::type)
                .isEqualTo(AssociationType.PROJECT_PERSON_PERIOD);
        assertThat(associated.contextLabel()).contains("不计入项目汇总");
        assertThat(associated.totals().travelAmount()).isEqualByComparingTo("100.00");
        assertThat(associated.totals().reimbursementAmount()).isEqualByComparingTo("0");

        SubjectSelectionTokenService tokens = new SubjectSelectionTokenService(10);
        String token = tokens.issue(EMPLOYEE_NO, USER_ID, SESSION_ID, BusinessSubjectType.PERSON);
        ReportDatasetService datasets = mock(ReportDatasetService.class);
        BusinessSnapshotReferenceValidationService validation =
                mock(BusinessSnapshotReferenceValidationService.class);
        CompositeReportTaskService tasks = mock(CompositeReportTaskService.class);
        when(datasets.list()).thenReturn(List.of(
                dataset("PERSON_TRAVEL"), dataset("PERSON_PUNCH"), dataset("PERSON_REIMBURSEMENT")
        ));
        when(validation.validate(any())).thenReturn(true);
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId("person-pdf-1");
        task.setFormat("PDF");
        task.setStatus("PENDING");
        task.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(tasks.create(any())).thenReturn(task);
        BusinessAssistantReportService reports = new BusinessAssistantReportService(
                tokens, datasets, validation, tasks
        );

        var artifact = reports.createPersonReport(new BusinessAssistantReportService.PersonReportCommand(
                new BusinessAssistantReportService.ReportIdentity(
                        "run-1", USER_ID, SESSION_ID, "Bearer current-user", Map.of(), token
                ),
                "PDF",
                Map.of("projectCode", PROJECT_CODE,
                        "startDate", "2026-08-01", "endDate", "2026-08-31"),
                false,
                List.of(
                        module(PersonBusinessQueryService.DatasetType.TRAVEL, "PERSON_TRAVEL", true),
                        module(PersonBusinessQueryService.DatasetType.PUNCH, "PERSON_PUNCH", true),
                        new PersonBusinessQueryService.ModuleResult(
                                PersonBusinessQueryService.DatasetType.REIMBURSEMENT,
                                "PERSON_REIMBURSEMENT",
                                PersonBusinessQueryService.ModuleStatus.FAILED,
                                false, null, null
                        )
                )
        ));

        ArgumentCaptor<CompositeReportTaskService.CreateCommand> command =
                ArgumentCaptor.forClass(CompositeReportTaskService.CreateCommand.class);
        verify(tasks).create(command.capture());
        assertThat(command.getValue().plannedReport().plan().format()).isEqualTo("PDF");
        assertThat(command.getValue().plannedReport().plan().dataComplete()).isFalse();
        assertThat(command.getValue().plannedReport().plan().sections())
                .extracting(section -> section.status() + ":" + section.safeMessage())
                .containsExactly("REUSED:null", "REUSED:null", "FAILED:数据查询失败，章节未纳入");
        assertThat(artifact.format()).isEqualTo("PDF");
        assertThat(artifact.status()).isEqualTo(BlockStatus.PENDING);
        assertThat(artifact.dataComplete()).isFalse();
    }

    @Test
    void applicationExposesExactlyOneBusinessAssistantImplementation() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BusinessAssistantService.class));

        assertThat(scanner.findCandidateComponents("org.example.ai.agent"))
                .extracting(definition -> definition.getBeanClassName())
                .containsExactly("org.example.ai.agent.business.impl.BusinessAssistantServiceImpl");
    }

    private void assertPersonStory(PersonBusinessQueryService.Result result) {
        assertThat(result.travelSummary().tripCount().value()).isEqualTo(6);
        assertThat(result.travelSummary().totalAmount().value()).isEqualByComparingTo("600.60");
        // 报销金额口径明确区分：申请、审批、实付。
        assertThat(result.reimbursementSummary().requestedAmount().value()).isEqualByComparingTo("1000.00");
        assertThat(result.reimbursementSummary().approvedAmount().value()).isEqualByComparingTo("900.00");
        assertThat(result.reimbursementSummary().paidAmount().value()).isEqualByComparingTo("800.00");
        assertThat(result.attendance()).extracting(day -> day.date() + ":" + day.determination())
                .containsExactly(
                        "2026-08-03:MISSING_PUNCH",
                        "2026-08-04:LEAVE_EXEMPT",
                        "2026-08-05:TRAVEL_EXEMPT"
                );
    }

    private PersonBusinessQueryService.Command personCommand(String token, boolean refresh) {
        List<PersonBusinessQueryService.DatasetPlan> plans = Arrays.stream(
                        PersonBusinessQueryService.DatasetType.values())
                .map(type -> new PersonBusinessQueryService.DatasetPlan(
                        type, datasetCode(type),
                        Map.of("startDate", "2026-08-03", "endDate", "2026-08-05"),
                        "DAY", Set.of(factCode(type)),
                        type == PersonBusinessQueryService.DatasetType.TRAVEL
                                || type == PersonBusinessQueryService.DatasetType.PUNCH
                                || type == PersonBusinessQueryService.DatasetType.REIMBURSEMENT
                ))
                .toList();
        return new PersonBusinessQueryService.Command(
                "run-1", USER_ID, SESSION_ID, "Bearer current-user", Map.of(), token, refresh, plans
        );
    }

    private Map<String, Object> safeFacts(String datasetCode) {
        return Map.of(
                "calculation", calculationFor(datasetCode),
                "display", Map.of(), "export", Map.of(), "model", Map.of()
        );
    }

    private Map<String, Object> calculationFor(String datasetCode) {
        PersonBusinessQueryService.DatasetType type = typeFor(datasetCode);
        Object records = switch (type) {
            case TRAVEL -> List.of(
                    trip("2026-07-01", "100.10"), trip("2026-07-02", "100.10"),
                    trip("2026-07-03", "100.10"), trip("2026-07-04", "100.10"),
                    trip("2026-07-05", "100.10"), trip("2026-08-05", "100.10")
            );
            case PUNCH -> List.of();
            case LEAVE -> List.of(exemption("2026-08-04"));
            case SCHEDULE -> List.of(
                    schedule("2026-08-03"), schedule("2026-08-04"), schedule("2026-08-05")
            );
            case CALENDAR -> List.of(
                    calendar("2026-08-03"), calendar("2026-08-04"), calendar("2026-08-05")
            );
            case REIMBURSEMENT -> List.of(Map.of(
                    "requestedAmount", new BigDecimal("1000.00"),
                    "approvedAmount", new BigDecimal("900.00"),
                    "paidAmount", new BigDecimal("800.00")
            ));
        };
        return Map.of(factCode(type), records);
    }

    private Map<String, Object> trip(String date, String amount) {
        return Map.of(
                "startAt", date + "T09:00:00", "endAt", date + "T18:00:00",
                "approvalStatus", "APPROVED", "amount", new BigDecimal(amount)
        );
    }

    private Map<String, Object> exemption(String date) {
        return Map.of(
                "startAt", date + "T09:00:00", "endAt", date + "T18:00:00",
                "approvalStatus", "APPROVED"
        );
    }

    private Map<String, Object> schedule(String date) {
        return Map.of("date", date, "startTime", "09:00", "endTime", "18:00");
    }

    private Map<String, Object> calendar(String date) {
        return Map.of("date", date, "workingDay", true);
    }

    private DatasetExecutionResult execution(
            DatasetExecutionRequest request,
            DatasetExecutionStatus status,
            boolean complete,
            Map<String, Object> safeFacts) {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        request.userId(), request.sessionId(), request.subjectType(), request.subjectId(),
                        request.datasetCode(), "b".repeat(64), "query-" + request.datasetCode(), 1L,
                        "c".repeat(64), POLICY_CHECKSUM
                ),
                status, complete, safeFacts, "workflow-run-1", null, null, "完成", "d".repeat(64)
        );
    }

    private Map<String, Object> channels(String key, Object value) {
        return Map.of(
                "calculation", Map.of(), "display", Map.of(key, value),
                "export", Map.of(), "model", Map.of()
        );
    }

    private BusinessSnapshot snapshot(String id, String datasetCode) {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId(id);
        snapshot.setDatasetCode(datasetCode);
        snapshot.setFieldPolicyChecksum(POLICY_CHECKSUM);
        return snapshot;
    }

    private AuthorizedSubjectCandidate project(String id, String code) {
        return new AuthorizedSubjectCandidate(
                BusinessSubjectType.PROJECT, id, code + " 项目", null, null, code, "DELIVERY"
        );
    }

    private AuthorizedSubjectCandidate person() {
        return new AuthorizedSubjectCandidate(
                BusinessSubjectType.PERSON, EMPLOYEE_NO, "张三", "E1***86", "工程部", null, null
        );
    }

    private String datasetCode(PersonBusinessQueryService.DatasetType type) {
        return "PERSON_" + type.name();
    }

    private PersonBusinessQueryService.DatasetType typeFor(String datasetCode) {
        return PersonBusinessQueryService.DatasetType.valueOf(datasetCode.substring("PERSON_".length()));
    }

    private String factCode(PersonBusinessQueryService.DatasetType type) {
        return switch (type) {
            case TRAVEL -> PersonBusinessQueryService.TRAVEL_RECORDS;
            case PUNCH -> PersonBusinessQueryService.PUNCH_RECORDS;
            case LEAVE -> PersonBusinessQueryService.LEAVE_RECORDS;
            case SCHEDULE -> PersonBusinessQueryService.SCHEDULE_RECORDS;
            case CALENDAR -> PersonBusinessQueryService.CALENDAR_RECORDS;
            case REIMBURSEMENT -> PersonBusinessQueryService.REIMBURSEMENT_RECORDS;
        };
    }

    private ProjectRecordAssociationService.BusinessRecord record(
            String id, String projectCode, LocalDate rosterStart,
            String travelAmount, String reimbursementAmount) {
        return new ProjectRecordAssociationService.BusinessRecord(
                id, projectCode, null, LocalDate.of(2026, 8, 15),
                rosterStart, LocalDate.of(2026, 12, 31),
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal(travelAmount), new BigDecimal(reimbursementAmount)
        );
    }

    private ReportDataset dataset(String code) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode(code);
        dataset.setEnabled(true);
        dataset.setFieldPolicyChecksum(POLICY_CHECKSUM);
        return dataset;
    }

    private PersonBusinessQueryService.ModuleResult module(
            PersonBusinessQueryService.DatasetType type, String code, boolean complete) {
        return new PersonBusinessQueryService.ModuleResult(
                type, code, PersonBusinessQueryService.ModuleStatus.REUSED,
                complete, "snapshot-" + code, POLICY_CHECKSUM
        );
    }
}
