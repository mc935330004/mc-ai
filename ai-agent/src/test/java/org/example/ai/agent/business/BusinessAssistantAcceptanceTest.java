package org.example.ai.agent.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.answer.BusinessAnswerModelService;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService;
import org.example.ai.agent.business.intent.BusinessIntentValidator;
import org.example.ai.agent.business.intent.BusinessQueryIntent;
import org.example.ai.agent.business.intent.BusinessQueryIntentResolver;
import org.example.ai.agent.business.panorama.PanoramaDatasetExecutor;
import org.example.ai.agent.business.panorama.ProjectIssueRuleService;
import org.example.ai.agent.business.panorama.ProjectPanoramaExecutionService;
import org.example.ai.agent.business.panorama.ProjectPanoramaProfileService;
import org.example.ai.agent.business.panorama.ProjectPanoramaSnapshotService;
import org.example.ai.agent.business.panorama.ProjectPanoramaSnapshotReuseService;
import org.example.ai.agent.business.panorama.ProjectSubjectAuthorizationService;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.person.AttendanceReconciliationService;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.person.PersonDatasetPlanService;
import org.example.ai.agent.business.person.PersonDatasetSelectionService;
import org.example.ai.agent.business.person.PersonSnapshotReuseService;
import org.example.ai.agent.business.person.model.AttendanceDayResult;
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
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.stream.ChatResponseAccumulator;
import org.example.ai.agent.chat.stream.ResponseStreamContext;
import org.example.ai.agent.chat.stream.ResponseStreamEventFactory;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
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
    void myProjectsShowsPagedAuthorizedCandidatesAndExecutesNoBusinessModule() {
        FacadeHarness facade = new FacadeHarness();
        facade.intents(new BusinessQueryIntent(
                BusinessSubjectType.PROJECT, null, null, null,
                LocalDate.now().getYear(), null, null, List.of(), false, null, false, false
        ));
        when(facade.projects.search(any())).thenReturn(new SubjectDirectoryPage(
                true,
                List.of(project("project-1", PROJECT_CODE), project("project-2", "XXXT2674050")),
                1, 2, 6, true
        ));

        facade.handle("我的项目");

        assertThat(facade.savedJson()).contains(
                "SUBJECT_CANDIDATES", PROJECT_CODE, "XXXT2674050", "选择凭证"
        );
        verify(facade.panoramaExecution, never()).execute(any(), any());
        verify(facade.personQuery, never()).query(any());
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
        when(executor.execute(any(), anyInt())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return "CONTRACT".equals(request.datasetCode())
                    ? new PanoramaDatasetExecutor.Result(
                    DatasetExecutionStatus.SUCCESS,
                    execution(request, DatasetExecutionStatus.SUCCESS, true,
                            channels("contractAmount", new BigDecimal("8600.00")))
            )
                    : new PanoramaDatasetExecutor.Result(DatasetExecutionStatus.DENIED, null);
        });
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

        FacadeHarness facade = new FacadeHarness(
                new SubjectSelectionTokenService(10), service,
                mock(PersonBusinessQueryService.class), mock(BusinessAssistantReportService.class)
        );
        facade.intents(new BusinessQueryIntent(
                BusinessSubjectType.PROJECT, PROJECT_CODE, null, null,
                null, null, null, List.of(), false, null, false, false
        ));
        when(facade.projects.search(any())).thenReturn(new SubjectDirectoryPage(
                true, List.of(project("project-1", PROJECT_CODE)), 1, 20, 1, false
        ));
        when(facade.datasets.list()).thenReturn(List.of(
                dataset("CONTRACT"), dataset("CASH_FLOW")
        ));

        facade.handle("查询 XXXT2674040 项目全景");

        assertThat(facade.savedJson()).contains(
                PROJECT_CODE, "CONTRACT", "contractAmount", "8600.00",
                "CASH_FLOW", "DENIED", "\"dataComplete\":false"
        );
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

        FacadeHarness facade = new FacadeHarness(
                tokens, mock(ProjectPanoramaExecutionService.class), service,
                mock(BusinessAssistantReportService.class)
        );
        facade.intents(
                personIntent(false, null),
                personIntent(true, null)
        );
        when(facade.people.search(any())).thenReturn(new SubjectDirectoryPage(
                true, List.of(person()), 1, 20, 1, false
        ));
        when(facade.datasets.list()).thenReturn(personDatasets());

        facade.handle("我 6 次出差的总金额、缺卡日期和报销总金额");
        facade.handle("明确刷新上述数据");

        assertThat(facade.savedJsons().get(0)).contains(
                "出差次数", "6", "出差总金额", "600.60",
                "报销申请金额", "1000.00", "报销审批金额", "900.00",
                "报销支付金额", "800.00", "2026-08-03", "MISSING_PUNCH"
        );
        verify(reuse, org.mockito.Mockito.times(12)).reuse(any());
        verify(execution, org.mockito.Mockito.times(12)).execute(any());
    }

    @Test
    void personProjectPeriodPdfKeepsProjectContextAndDisclosesIncompleteSection() {
        SubjectSelectionTokenService tokens = new SubjectSelectionTokenService(10);
        String token = tokens.issue(EMPLOYEE_NO, USER_ID, SESSION_ID, BusinessSubjectType.PERSON);
        ReportDatasetService datasets = mock(ReportDatasetService.class);
        BusinessSnapshotReferenceValidationService validation =
                mock(BusinessSnapshotReferenceValidationService.class);
        CompositeReportTaskService tasks = mock(CompositeReportTaskService.class);
        when(datasets.list()).thenReturn(personDatasets());
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
                        module(PersonBusinessQueryService.DatasetType.LEAVE, "PERSON_LEAVE", true),
                        module(PersonBusinessQueryService.DatasetType.SCHEDULE, "PERSON_SCHEDULE", true),
                        module(PersonBusinessQueryService.DatasetType.CALENDAR, "PERSON_CALENDAR", true),
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
        assertThat(command.getValue().canonicalQuery()).containsEntry("projectCode", PROJECT_CODE);
        assertThat(command.getValue().plannedReport().plan().sections())
                .extracting(section -> section.status() + ":" + section.safeMessage())
                .containsExactly(
                        "REUSED:null", "REUSED:null", "REUSED:null",
                        "REUSED:null", "REUSED:null", "FAILED:数据查询失败，章节未纳入"
                );
        assertThat(artifact.format()).isEqualTo("PDF");
        assertThat(artifact.status()).isEqualTo(BlockStatus.PENDING);
        assertThat(artifact.dataComplete()).isFalse();

        PersonBusinessQueryService.Result incomplete = incompletePersonResult();
        FacadeHarness facade = new FacadeHarness(
                tokens, mock(ProjectPanoramaExecutionService.class),
                mock(PersonBusinessQueryService.class), reports
        );
        facade.intents(personIntent(false, "PDF"));
        when(facade.people.search(any())).thenReturn(new SubjectDirectoryPage(
                true, List.of(person()), 1, 20, 1, false
        ));
        when(facade.datasets.list()).thenReturn(personDatasets());
        when(facade.personQuery.query(any())).thenReturn(incomplete);

        facade.handle("查询张三在项目期间的出差、打卡和报销并生成 PDF");

        assertThat(facade.savedJson()).contains(
                "PDF", "person-pdf-1", "PERSON_REIMBURSEMENT",
                "FAILED", "数据不完整", "\"dataComplete\":false"
        );
        ArgumentCaptor<PersonBusinessQueryService.Command> personCommand =
                ArgumentCaptor.forClass(PersonBusinessQueryService.Command.class);
        verify(facade.personQuery).query(personCommand.capture());
        assertThat(personCommand.getValue().plans()).allSatisfy(plan ->
                assertThat(plan.canonicalInput()).containsEntry("projectCode", PROJECT_CODE)
        );
        verify(tasks, org.mockito.Mockito.times(2)).create(any());
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

    private BusinessQueryIntent personIntent(boolean refresh, String exportFormat) {
        boolean projectContext = exportFormat != null;
        return new BusinessQueryIntent(
                BusinessSubjectType.PERSON,
                projectContext ? PROJECT_CODE : null,
                projectContext ? "张三" : null,
                null, null,
                LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 5),
                List.of("TRAVEL", "ATTENDANCE", "REIMBURSEMENT"),
                refresh, exportFormat, false, projectContext
        );
    }

    private List<ReportDataset> personDatasets() {
        return Arrays.stream(PersonBusinessQueryService.DatasetType.values())
                .map(type -> dataset(datasetCode(type)))
                .toList();
    }

    private PersonBusinessQueryService.Result incompletePersonResult() {
        List<AttendanceDayResult> attendance = List.of(
                new AttendanceDayResult(
                        LocalDate.of(2026, 8, 3), "NO_PUNCH", "NONE",
                        "MISSING_PUNCH", List.of(PersonBusinessQueryService.PUNCH_RECORDS)
                )
        );
        return new PersonBusinessQueryService.Result(
                new PersonBusinessQueryService.TravelSummary(
                        PersonBusinessQueryService.Metric.complete(6),
                        PersonBusinessQueryService.Metric.complete(new BigDecimal("600.60"))
                ),
                new PersonBusinessQueryService.ReimbursementSummary(
                        PersonBusinessQueryService.Metric.incomplete(),
                        PersonBusinessQueryService.Metric.incomplete(),
                        PersonBusinessQueryService.Metric.incomplete()
                ),
                attendance,
                List.of(
                        module(PersonBusinessQueryService.DatasetType.TRAVEL, "PERSON_TRAVEL", true),
                        module(PersonBusinessQueryService.DatasetType.PUNCH, "PERSON_PUNCH", true),
                        module(PersonBusinessQueryService.DatasetType.LEAVE, "PERSON_LEAVE", true),
                        module(PersonBusinessQueryService.DatasetType.SCHEDULE, "PERSON_SCHEDULE", true),
                        module(PersonBusinessQueryService.DatasetType.CALENDAR, "PERSON_CALENDAR", true),
                        new PersonBusinessQueryService.ModuleResult(
                                PersonBusinessQueryService.DatasetType.REIMBURSEMENT,
                                "PERSON_REIMBURSEMENT",
                                PersonBusinessQueryService.ModuleStatus.FAILED,
                                false, null, null
                        )
                )
        );
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

    private ReportDataset dataset(String code) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode(code);
        dataset.setDatasetName(code);
        dataset.setDomainCode(code);
        dataset.setSubjectTypesJson("[\"PERSON\",\"PROJECT\"]");
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

    private final class FacadeHarness {
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final TrackedChatClientService intentModel = mock(TrackedChatClientService.class);
        private final ChatResponse intentResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        private final ProjectDirectoryService projects = mock(ProjectDirectoryService.class);
        private final AuthorizedPersonDirectoryService people = mock(AuthorizedPersonDirectoryService.class);
        private final DepartmentDirectoryService departments = mock(DepartmentDirectoryService.class);
        private final SubjectSelectionTokenService tokens;
        private final ProjectPanoramaExecutionService panoramaExecution;
        private final ProjectPanoramaSnapshotReuseService panoramaReuse =
                mock(ProjectPanoramaSnapshotReuseService.class);
        private final PersonBusinessQueryService personQuery;
        private final DepartmentBusinessQueryService departmentQuery = mock(DepartmentBusinessQueryService.class);
        private final ReportDatasetService datasets = mock(ReportDatasetService.class);
        private final BusinessAssistantReportService reportService;
        private final ConversationStateService conversationState = mock(ConversationStateService.class);
        private final AiChatSessionService chatSession = mock(AiChatSessionService.class);
        private final AgentStreamSession stream = mock(AgentStreamSession.class);
        private final ChatResponseAccumulator accumulator = new ChatResponseAccumulator(
                new ResponseStreamContext("response-1", "run-1", "conversation-1")
        );
        private final BusinessAssistantService service;

        private FacadeHarness() {
            this(
                    new SubjectSelectionTokenService(10),
                    mock(ProjectPanoramaExecutionService.class),
                    mock(PersonBusinessQueryService.class),
                    mock(BusinessAssistantReportService.class)
            );
        }

        private FacadeHarness(
                SubjectSelectionTokenService tokens,
                ProjectPanoramaExecutionService panoramaExecution,
                PersonBusinessQueryService personQuery,
                BusinessAssistantReportService reportService) {
            this.tokens = tokens;
            this.panoramaExecution = panoramaExecution;
            this.personQuery = personQuery;
            this.reportService = reportService;
            BusinessQueryIntentResolver intents = new BusinessQueryIntentResolver(
                    intentModel, objectMapper, new BusinessIntentValidator()
            );
            SubjectResolutionService subjects = new SubjectResolutionService(
                    projects, people, departments, tokens
            );
            BusinessAnswerModelService answerModel = mock(BusinessAnswerModelService.class);
            when(answerModel.generate(any())).thenReturn("确定性结果说明");
            when(stream.getMessageId()).thenReturn("response-1");
            when(stream.getChatResponseAccumulator()).thenReturn(accumulator);
            when(stream.getResponseEventFactory()).thenReturn(mock(ResponseStreamEventFactory.class));
            try {
                doAnswer(invocation -> {
                    accumulator.completeBlock(invocation.getArgument(0));
                    return null;
                }).when(stream).publishResponseBlock(any());
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            doAnswer(invocation -> {
                accumulator.setDataComplete(invocation.getArgument(0));
                return null;
            }).when(stream).setResponseDataComplete(org.mockito.ArgumentMatchers.anyBoolean());
            service = new org.example.ai.agent.business.impl.BusinessAssistantServiceImpl(
                    intents, subjects, panoramaExecution, panoramaReuse,
                    personQuery, new PersonDatasetSelectionService(),
                    new PersonDatasetPlanService(objectMapper), departmentQuery,
                    datasets, new DeterministicBusinessAnswerComposer(answerModel), reportService,
                    conversationState, chatSession, objectMapper
            );
        }

        private void intents(BusinessQueryIntent... values) {
            String[] json = Arrays.stream(values).map(value -> {
                try {
                    return objectMapper.writeValueAsString(value);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).toArray(String[]::new);
            when(intentResponse.getResult().getOutput().getText())
                    .thenReturn(json[0], Arrays.copyOfRange(json, 1, json.length));
            when(intentModel.call(
                    any(), anyString(), anyString(), any(ChatOptions.Builder.class)
            )).thenReturn(intentResponse);
        }

        private void handle(String question) {
            AgentRequest request = new AgentRequest();
            request.setConversationId("conversation-1");
            request.setUserId(USER_ID);
            request.setUserQuestion(question);
            request.setAuthorization("Bearer current-user");
            request.setModelCode("model-1");
            service.handle(request, stream, "run-1");
        }

        private List<String> savedJsons() {
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(chatSession, org.mockito.Mockito.atLeastOnce()).saveAssistantMessage(
                    any(), any(), any(), any(), any(), any(), json.capture()
            );
            return json.getAllValues();
        }

        private String savedJson() {
            return savedJsons().get(0);
        }
    }
}
