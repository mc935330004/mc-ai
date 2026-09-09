package org.example.ai.agent.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.answer.BusinessAnswerModelService;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService.DepartmentQueryStatus;
import org.example.ai.agent.business.person.model.MultiPersonSummary.Aggregate;
import org.example.ai.agent.business.person.model.MultiPersonSummary.AnomalyPerson;
import org.example.ai.agent.business.person.model.MultiPersonSummary.PersonQueryStatus;
import org.example.ai.agent.business.intent.BusinessQueryIntent;
import org.example.ai.agent.business.intent.BusinessQueryIntentResolver;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.panorama.ProjectPanoramaExecutionService;
import org.example.ai.agent.business.panorama.ProjectPanoramaSnapshotReuseService;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.person.PersonDatasetSelectionService;
import org.example.ai.agent.business.report.BusinessAssistantReportService;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.CompositeReportTaskService;
import org.example.ai.agent.business.snapshot.BusinessSnapshotReferenceValidationService;
import org.example.ai.agent.business.subject.SubjectResolutionService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import org.example.ai.agent.chat.protocol.block.ArtifactBlock;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.stream.ChatResponseAccumulator;
import org.example.ai.agent.chat.stream.ResponseStreamEventFactory;
import org.example.ai.agent.chat.stream.ResponseStreamContext;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessAssistantServiceTest {

    @Test
    void departmentSummaryShowsCountsAndCompleteTotalsWithoutPeopleNames() throws Exception {
        Fixture fixture = departmentFixture(false, null, completedDepartment());

        fixture.service.handle(request("查询部门汇总"), fixture.stream, "run-1");

        String json = savedDepartmentJson(fixture);
        assertThat(json).contains("DEPARTMENT_SUMMARY", "authorizedPeople", "loadedPeople", "processedPeople",
                "successPeople", "partialPeople", "failedPeople", "timeoutPeople", "cancelledPeople",
                "tripCount", "travelAmount", "reimbursementAmount", "1200", "900")
                .doesNotContain("张三", "10***01", "department_anomalies");
        var command = composedDepartment(fixture);
        assertThat(command.dataComplete()).isTrue();
        assertThat(command.narrativeRequired()).isFalse();
        assertThat(command.datasets().get(0).displayFacts()).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("authorizedPeople", 3L), Map.entry("loadedPeople", 3), Map.entry("processedPeople", 3),
                Map.entry("successPeople", 3), Map.entry("partialPeople", 0), Map.entry("failedPeople", 0),
                Map.entry("timeoutPeople", 0), Map.entry("cancelledPeople", 0), Map.entry("tripCount", 6),
                Map.entry("travelAmount", new java.math.BigDecimal("1200")),
                Map.entry("reimbursementAmount", new java.math.BigDecimal("900"))));
        assertThat(command.datasets().get(0).modelFacts()).isEmpty();
        assertThat(command.datasets().get(0).status()).isEqualTo(
                org.example.ai.agent.business.model.DatasetExecutionStatus.SUCCESS);
        verify(fixture.modelService, never()).generate(any());
        ArgumentCaptor<org.example.ai.agent.chat.memory.model.BusinessConversationState> state =
                ArgumentCaptor.forClass(org.example.ai.agent.chat.memory.model.BusinessConversationState.class);
        verify(fixture.conversationStateService).saveState(any(), any(), state.capture());
        assertThat(state.getValue().getLastInput()).containsEntry("selectionToken", "department-selection-token")
                .containsEntry("startDate", "2026-08-01").containsEntry("endDate", "2026-08-31");
        InOrder order = org.mockito.Mockito.inOrder(fixture.stream);
        order.verify(fixture.stream).startChatResponse();
        order.verify(fixture.stream).sendResponseSnapshot();
        order.verify(fixture.stream, org.mockito.Mockito.atLeastOnce()).publishResponseBlock(any());
        order.verify(fixture.stream).beginFinalization();
        order.verify(fixture.stream).sendResponseSnapshot();
        order.verify(fixture.stream).finishChatResponse();
    }

    @Test
    void departmentAnomalyQuestionShowsOnlyAllowedMaskedAnomalyPeople() throws Exception {
        Fixture fixture = departmentFixture(true, null, completedDepartment());

        fixture.service.handle(request("部门哪些人异常"), fixture.stream, "run-1");

        assertThat(savedDepartmentJson(fixture)).contains("department_anomalies", "张三（10***01）",
                "异常类型", "缺卡", "迟到").doesNotContain("李四", "20***02");
        var dataset = composedDepartment(fixture).datasets().get(0);
        assertThat(dataset.displayFacts().get("anomalyRows")).isEqualTo(List.of(
                Map.of("displayLabel", "张三（10***01）", "anomalyTypes", "缺卡、迟到")));
        assertThat(dataset.modelFacts()).isEmpty();
        verify(fixture.modelService, never()).generate(any());
    }

    @Test
    void departmentLimitExceededPromptsUserToNarrowScope() throws Exception {
        String instruction = "当前授权成员超过单次查询上限，请按下级部门或人员范围缩小查询";
        for (String safeMessage : java.util.Arrays.asList(instruction, null)) {
            Fixture fixture = departmentFixture(false, null, new DepartmentBusinessQueryService.Result(
                    DepartmentQueryStatus.LIMIT_EXCEEDED, false, 101, 0, 0,
                    new Aggregate(false, 0, null, null, null), Map.of(), List.of(), safeMessage));

            fixture.service.handle(request("查询部门汇总"), fixture.stream, "run-1");

            assertThat(savedDepartmentJson(fixture)).contains(instruction).doesNotContain("tripCount", "travelAmount");
            assertThat(composedDepartment(fixture).datasets().get(0).status()).isEqualTo(
                    org.example.ai.agent.business.model.DatasetExecutionStatus.FAILED);
        }
    }

    @Test
    void departmentPartialResultDoesNotPublishFormalTotals() throws Exception {
        Fixture fixture = departmentFixture(false, null, new DepartmentBusinessQueryService.Result(
                DepartmentQueryStatus.PARTIAL, false, 5, 5, 5,
                new Aggregate(false, 1, null, null, null),
                Map.of(PersonQueryStatus.SUCCESS, 1, PersonQueryStatus.PARTIAL, 1,
                        PersonQueryStatus.FAILED, 1, PersonQueryStatus.TIMEOUT, 1, PersonQueryStatus.CANCELLED, 1),
                List.of(), null));

        fixture.service.handle(request("查询部门汇总"), fixture.stream, "run-1");

        assertThat(savedDepartmentJson(fixture)).contains("\"dataComplete\":false")
                .doesNotContain("tripCount", "travelAmount", "reimbursementAmount");
        var dataset = composedDepartment(fixture).datasets().get(0);
        assertThat(dataset.dataComplete()).isFalse();
        assertThat(dataset.status()).isEqualTo(org.example.ai.agent.business.model.DatasetExecutionStatus.FAILED);
        assertThat(dataset.displayFacts()).containsEntry("authorizedPeople", 5L).containsEntry("processedPeople", 5)
                .containsEntry("successPeople", 1).containsEntry("partialPeople", 1).containsEntry("failedPeople", 1)
                .containsEntry("timeoutPeople", 1).containsEntry("cancelledPeople", 1);
    }

    @Test
    void departmentExportReturnsUnsupportedDatasetWithoutCreatingReportTask() throws Exception {
        Fixture fixture = departmentFixture(false, "PDF", completedDepartment());

        fixture.service.handle(request("导出部门汇总"), fixture.stream, "run-1");

        assertThat(savedDepartmentJson(fixture)).contains("DEPARTMENT_SUMMARY", "tripCount", "DEPARTMENT_REPORT",
                "本阶段暂不支持部门报告导出，请缩小到单个人员后导出", "\"dataComplete\":false");
        assertThat(composedDepartment(fixture).dataComplete()).isFalse();
        verify(fixture.reportTaskService, never()).create(any());
        verify(fixture.reportService, never()).createPersonReport(any());
        verify(fixture.reportService, never()).createProjectReport(any());
    }

    @Test
    void departmentQueryReceivesLiveStreamCancellationSignal() throws Exception {
        Fixture fixture = departmentFixture(true, null, completedDepartment());

        fixture.service.handle(request("部门异常汇总"), fixture.stream, "run-1");

        ArgumentCaptor<java.util.function.BooleanSupplier> stop =
                ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);
        ArgumentCaptor<DepartmentBusinessQueryService.Command> command =
                ArgumentCaptor.forClass(DepartmentBusinessQueryService.Command.class);
        verify(fixture.departmentBusinessQueryService).query(command.capture(), stop.capture());
        assertThat(stop.getValue().getAsBoolean()).isFalse();
        when(fixture.stream.shouldStopBusinessQuery()).thenReturn(true);
        assertThat(stop.getValue().getAsBoolean()).isTrue();
        assertThat(command.getValue().agentRunId()).isEqualTo("run-1");
        assertThat(command.getValue().userId()).isEqualTo("user-1");
        assertThat(command.getValue().sessionId()).isEqualTo("conversation-1");
        assertThat(command.getValue().authorization()).isEqualTo("Bearer current-user");
        assertThat(command.getValue().departmentSelectionToken()).isEqualTo("department-selection-token");
        assertThat(command.getValue().secureContext()).isEmpty();
        assertThat(command.getValue().refreshRequested()).isTrue();
        assertThat(command.getValue().anomalyPeopleRequested()).isTrue();
        assertThat(command.getValue().plans()).extracting(PersonBusinessQueryService.DatasetPlan::datasetCode)
                .containsExactly("CFG_TRAVEL", "CFG_PUNCH", "CFG_LEAVE", "CFG_SCHEDULE", "CFG_CALENDAR", "CFG_REIMBURSEMENT");
    }

    @Test
    void departmentResponseAndStoredJsonDoNotContainRawIdentifiersOrTokens() throws Exception {
        Fixture fixture = departmentFixture(true, null, completedDepartment());
        AgentRequest request = request("查询部门异常");
        request.setExtra(Map.of("departmentId", "raw-dept-987", "employeeNo", "raw-person-654"));

        fixture.service.handle(request, fixture.stream, "run-1");

        String json = savedDepartmentJson(fixture);
        assertThat(json).contains("DEPARTMENT_SUMMARY", "张三（10***01）")
                .doesNotContain("raw-dept-987", "raw-person-654", "department-selection-token",
                        "selectionToken", "Bearer current-user", "Authorization", "authorization");
        assertThat(fixture.objectMapper.writeValueAsString(fixture.accumulator.complete())).isEqualTo(json);
        verify(fixture.selectionTokenService, never()).resolve(any(), any(), any(), any());
    }

    @Test
    void departmentReimbursementShouldNotPublishTravelValues() throws Exception {
        Fixture fixture = departmentFixture(false, null, completedDepartment(), List.of("REIMBURSEMENT"));

        fixture.service.handle(request("查询部门报销"), fixture.stream, "run-1");

        DepartmentBusinessQueryService.Command query = capturedDepartmentCommand(fixture);
        assertThat(query.plans()).extracting(PersonBusinessQueryService.DatasetPlan::type)
                .containsExactly(PersonBusinessQueryService.DatasetType.REIMBURSEMENT);
        assertThat(composedDepartment(fixture).datasets().get(0).displayFacts())
                .containsEntry("reimbursementAmount", new java.math.BigDecimal("900"))
                .doesNotContainKeys("tripCount", "travelAmount");
    }

    @Test
    void departmentAttendanceShouldHideDependencyTravelAndShowRequestedAnomalies() throws Exception {
        Fixture fixture = departmentFixture(true, null, completedDepartment(), List.of("ATTENDANCE"));

        fixture.service.handle(request("查询部门考勤异常"), fixture.stream, "run-1");

        DepartmentBusinessQueryService.Command query = capturedDepartmentCommand(fixture);
        assertThat(query.plans()).hasSize(5);
        assertThat(query.plans()).filteredOn(PersonBusinessQueryService.DatasetPlan::userRequested)
                .extracting(PersonBusinessQueryService.DatasetPlan::type)
                .containsExactly(PersonBusinessQueryService.DatasetType.PUNCH);
        DeterministicBusinessAnswerComposer.ComposeCommand answer = composedDepartment(fixture);
        assertThat(answer.datasets().get(0).displayFacts())
                .containsKey("anomalyRows")
                .doesNotContainKeys("tripCount", "travelAmount", "reimbursementAmount");
        assertThat(answer.tableDefinitions()).hasSize(1);
    }

    private Fixture departmentFixture(boolean anomalies, String export, DepartmentBusinessQueryService.Result result) {
        return departmentFixture(anomalies, export, result, List.of());
    }

    private Fixture departmentFixture(
            boolean anomalies,
            String export,
            DepartmentBusinessQueryService.Result result,
            List<String> datasetCodes) {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                BusinessSubjectType.DEPARTMENT, null, null, null, null,
                java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31),
                datasetCodes, true, export, anomalies));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(new SubjectResolutionResult(
                SubjectResolutionState.RESOLVED,
                new SubjectCandidate(BusinessSubjectType.DEPARTMENT, "department-selection-token", "工程部",
                        null, "工程部", null, null), List.of(), 1, 20, 1, false, "主体已定位"));
        when(fixture.reportDatasetService.list()).thenReturn(personDatasets());
        when(fixture.departmentBusinessQueryService.query(any(), any())).thenReturn(result);
        return fixture;
    }

    private DepartmentBusinessQueryService.Command capturedDepartmentCommand(Fixture fixture) {
        ArgumentCaptor<DepartmentBusinessQueryService.Command> command =
                ArgumentCaptor.forClass(DepartmentBusinessQueryService.Command.class);
        verify(fixture.departmentBusinessQueryService).query(command.capture(), any());
        return command.getValue();
    }

    private DepartmentBusinessQueryService.Result completedDepartment() {
        return new DepartmentBusinessQueryService.Result(DepartmentQueryStatus.COMPLETED, true, 3, 3, 3,
                new Aggregate(true, 3, 6, new java.math.BigDecimal("1200"), new java.math.BigDecimal("900")),
                Map.of(PersonQueryStatus.SUCCESS, 3),
                List.of(new AnomalyPerson("张三（10***01）", List.of("缺卡", "迟到"))), null);
    }

    private String savedDepartmentJson(Fixture fixture) {
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(fixture.chatSessionService).saveAssistantMessage(any(), any(), any(), any(), any(), any(), json.capture());
        return json.getValue();
    }

    private DeterministicBusinessAnswerComposer.ComposeCommand composedDepartment(Fixture fixture) {
        ArgumentCaptor<DeterministicBusinessAnswerComposer.ComposeCommand> command =
                ArgumentCaptor.forClass(DeterministicBusinessAnswerComposer.ComposeCommand.class);
        verify(fixture.composer).compose(command.capture());
        return command.getValue();
    }

    @Test
    void reportCommandsMustNotExposeAuthorizationSelectionTokenOrQueryValues() {
        BusinessAssistantReportService.ReportIdentity identity =
                new BusinessAssistantReportService.ReportIdentity(
                        "run-1", "user-1", "conversation-1", "Bearer secret-token",
                        Map.of("role", "secret-role"), "secret-selection-token"
                );
        BusinessAssistantReportService.PersonReportCommand command =
                new BusinessAssistantReportService.PersonReportCommand(
                        identity, "PDF", Map.of("employeeNo", "raw-employee-no"),
                        false, List.of(personModule(
                        PersonBusinessQueryService.DatasetType.TRAVEL,
                        PersonBusinessQueryService.ModuleStatus.REUSED
                ))
                );

        assertThat(identity.toString())
                .doesNotContain("secret-token", "secret-role", "secret-selection-token");
        assertThat(command.toString())
                .doesNotContain("secret-token", "secret-role", "secret-selection-token", "raw-employee-no");
    }

    @Test
    void resolvedProjectReportSectionMustUseItsOwnFieldPolicyChecksum() {
        Fixture fixture = new Fixture();
        when(fixture.reportDatasetService.list()).thenReturn(List.of(dataset("CONTRACT")));
        when(fixture.selectionTokenService.resolve(
                "project-token", "user-1", "conversation-1", BusinessSubjectType.PROJECT
        )).thenReturn(java.util.Optional.of("project-raw-1"));
        ProjectPanoramaResult panorama = new ProjectPanoramaResult(
                1L, "a".repeat(64), "panorama-1", PanoramaExecutionState.COMPLETE,
                true, true, List.of(), List.of(), List.of(
                new ProjectPanoramaResult.ModuleResult(
                        "CONTRACT", true,
                        org.example.ai.agent.business.model.DatasetExecutionStatus.SUCCESS,
                        true, "snapshot-contract", null,
                        Map.of("amount", 8600), Map.of(), null
                )
        ));

        assertThatThrownBy(() -> fixture.reportService.createProjectReport(
                new BusinessAssistantReportService.ProjectReportCommand(
                        new BusinessAssistantReportService.ReportIdentity(
                                "run-1", "user-1", "conversation-1", "Bearer current-user",
                                Map.of(), "project-token"
                        ),
                        "DELIVERY", "P-1001", "PDF", Map.of("projectYear", 2026), false, panorama
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldPolicyChecksum");
        verify(fixture.reportTaskService, never()).create(any());
    }

    @Test
    void failedProjectReportSectionMustUseCurrentRegisteredFieldPolicyChecksum() {
        Fixture fixture = new Fixture();
        when(fixture.reportDatasetService.list()).thenReturn(List.of(dataset("CONTRACT")));
        when(fixture.selectionTokenService.resolve(
                "project-token", "user-1", "conversation-1", BusinessSubjectType.PROJECT
        )).thenReturn(java.util.Optional.of("project-raw-1"));
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId("task-failed-section");
        task.setFormat("PDF");
        task.setStatus("PENDING");
        task.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(fixture.reportTaskService.create(any())).thenReturn(task);
        ProjectPanoramaResult panorama = new ProjectPanoramaResult(
                1L, "a".repeat(64), "panorama-1", PanoramaExecutionState.FAILED,
                false, false, List.of("CONTRACT"), List.of(), List.of(
                new ProjectPanoramaResult.ModuleResult(
                        "CONTRACT", true,
                        org.example.ai.agent.business.model.DatasetExecutionStatus.FAILED,
                        false, null, null, Map.of(), Map.of(), "e".repeat(64)
                )
        ));

        fixture.reportService.createProjectReport(
                new BusinessAssistantReportService.ProjectReportCommand(
                        new BusinessAssistantReportService.ReportIdentity(
                                "run-1", "user-1", "conversation-1", "Bearer current-user",
                                Map.of(), "project-token"
                        ),
                        "DELIVERY", "P-1001", "PDF", Map.of("projectYear", 2026), false, panorama
                )
        );

        ArgumentCaptor<CompositeReportTaskService.CreateCommand> command =
                ArgumentCaptor.forClass(CompositeReportTaskService.CreateCommand.class);
        verify(fixture.reportTaskService).create(command.capture());
        assertThat(command.getValue().plannedReport().plan().sections().get(0).fieldPolicyChecksum())
                .isEqualTo("d".repeat(64));
        verify(fixture.snapshotReferenceValidationService, never()).validate(any());
    }

    @Test
    void trustedInheritedProjectSnapshotShouldBeReusedWithoutExecutingModules() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(projectIntent(false, null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedProject());
        when(fixture.panoramaSnapshotReuseService.reuse(any()))
                .thenReturn(java.util.Optional.of(projectPanorama()));
        AgentRequest request = request("继续分析这个项目");
        request.setInheritedInput(Map.of(
                "selectionToken", "project-token",
                "subjectType", "PROJECT",
                "panoramaSnapshotId", "panorama-1"
        ));

        fixture.service.handle(request, fixture.stream, "run-1");

        ArgumentCaptor<ProjectPanoramaSnapshotReuseService.ReuseCommand> command =
                ArgumentCaptor.forClass(ProjectPanoramaSnapshotReuseService.ReuseCommand.class);
        verify(fixture.panoramaSnapshotReuseService).reuse(command.capture());
        assertThat(command.getValue().panoramaSnapshotId()).isEqualTo("panorama-1");
        verify(fixture.panoramaExecutionService, never()).execute(any(), any());
    }

    @Test
    void explicitRefreshShouldBypassInheritedProjectSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(projectIntent(true, null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedProject());
        when(fixture.panoramaExecutionService.execute(any(), any())).thenReturn(projectPanorama());
        AgentRequest request = request("刷新项目数据");
        request.setInheritedInput(Map.of(
                "selectionToken", "project-token",
                "subjectType", "PROJECT",
                "panoramaSnapshotId", "panorama-1"
        ));

        fixture.service.handle(request, fixture.stream, "run-1");

        verify(fixture.panoramaSnapshotReuseService, never()).reuse(any());
        verify(fixture.panoramaExecutionService).execute(any(), any());
    }

    @Test
    void unusableInheritedProjectSnapshotShouldFallBackToModuleExecution() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(projectIntent(false, null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedProject());
        when(fixture.panoramaSnapshotReuseService.reuse(any()))
                .thenReturn(java.util.Optional.empty());
        when(fixture.panoramaExecutionService.execute(any(), any())).thenReturn(projectPanorama());
        AgentRequest request = request("继续分析这个项目");
        request.setInheritedInput(Map.of(
                "selectionToken", "project-token",
                "subjectType", "PROJECT",
                "panoramaSnapshotId", "expired-panorama"
        ));

        fixture.service.handle(request, fixture.stream, "run-1");

        verify(fixture.panoramaSnapshotReuseService).reuse(any());
        verify(fixture.panoramaExecutionService).execute(any(), any());
    }

    @Test
    void clientExtraProjectSnapshotMustNotEnterReuseService() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(projectIntent(false, "P-1001"));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedProject());
        when(fixture.panoramaExecutionService.execute(any(), any())).thenReturn(projectPanorama());
        AgentRequest request = request("查询 P-1001");
        request.setExtra(Map.of("panoramaSnapshotId", "forged-panorama"));

        fixture.service.handle(request, fixture.stream, "run-1");

        verify(fixture.panoramaSnapshotReuseService, never()).reuse(any());
        verify(fixture.panoramaExecutionService).execute(any(), any());
    }

    @Test
    void explicitProjectCodeMustIgnoreInheritedProjectSelectionAndSnapshot() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(projectIntent(false, "P-2002"));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(new SubjectResolutionResult(
                SubjectResolutionState.RESOLVED,
                new SubjectCandidate(
                        BusinessSubjectType.PROJECT, "project-b-token", "项目B",
                        null, null, "P-2002", "DELIVERY"
                ),
                List.of(), 1, 20, 1, false, "主体已定位"
        ));
        when(fixture.panoramaExecutionService.execute(any(), any())).thenReturn(projectPanorama());
        AgentRequest request = request("查询 P-2002");
        request.setInheritedInput(Map.of(
                "selectionToken", "project-a-token",
                "subjectType", "PROJECT",
                "panoramaSnapshotId", "project-a-panorama"
        ));

        fixture.service.handle(request, fixture.stream, "run-1");

        ArgumentCaptor<SubjectResolutionRequest> resolution =
                ArgumentCaptor.forClass(SubjectResolutionRequest.class);
        verify(fixture.subjectResolutionService).resolve(resolution.capture());
        assertThat(resolution.getValue().projectCode()).isEqualTo("P-2002");
        assertThat(resolution.getValue().selectionToken()).isNull();
        verify(fixture.panoramaSnapshotReuseService, never()).reuse(any());
        verify(fixture.panoramaExecutionService).execute(any(), any());
    }

    @Test
    void explicitCandidateTokenMustOverrideInheritedSelectionToken() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(projectIntent(false, null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(new SubjectResolutionResult(
                SubjectResolutionState.CANDIDATES, null, List.of(new SubjectCandidate(
                        BusinessSubjectType.PROJECT, "candidate-token", "候选项目",
                        null, null, "P-3003", "DELIVERY"
                )),
                1, 20, 1, false, "请选择主体"
        ));
        AgentRequest request = request("选择候选项目");
        request.setInheritedInput(Map.of(
                "selectionToken", "old-selection-token", "subjectType", "PROJECT"
        ));
        request.setExtra(Map.of("selectionToken", "new-selection-token"));

        fixture.service.handle(request, fixture.stream, "run-1");

        ArgumentCaptor<SubjectResolutionRequest> resolution =
                ArgumentCaptor.forClass(SubjectResolutionRequest.class);
        verify(fixture.subjectResolutionService).resolve(resolution.capture());
        assertThat(resolution.getValue().selectionToken()).isEqualTo("new-selection-token");
    }

    @Test
    void projectCandidatesMustNotExecuteBusinessModules() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                BusinessSubjectType.PROJECT, null, null, null,
                2026, null, null, List.of(), false, null, false
        ));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(new SubjectResolutionResult(
                SubjectResolutionState.CANDIDATES,
                null,
                List.of(new SubjectCandidate(
                        BusinessSubjectType.PROJECT, "selection-token", "示例项目",
                        null, null, "P-1001", "DELIVERY"
                )),
                1, 20, 1, false, "请从当前授权范围内选择主体"
        ));

        fixture.service.handle(request("我的项目"), fixture.stream, "run-1");

        verify(fixture.panoramaExecutionService, never()).execute(any(), any());
        verify(fixture.personBusinessQueryService, never()).query(any());
        verify(fixture.stream, org.mockito.Mockito.times(2)).sendResponseSnapshot();
        verify(fixture.stream).finishChatResponse();
        InOrder order = org.mockito.Mockito.inOrder(fixture.accumulator, fixture.stream);
        order.verify(fixture.accumulator).setContext(any());
        order.verify(fixture.stream).sendResponseSnapshot();
        order.verify(fixture.stream).sendResponseEvent(any());
        order.verify(fixture.stream).publishResponseBlock(any());
    }

    @Test
    void exactProjectShouldComposePanoramaReportAndKeepDeniedModuleWhenModelFails() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                BusinessSubjectType.PROJECT, "P-1001", null, null,
                2026, null, null, List.of(), false, "PDF", false
        ));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedProject());
        ProjectPanoramaResult panorama = new ProjectPanoramaResult(
                1L, "a".repeat(64), "panorama-1", PanoramaExecutionState.PARTIAL_SUCCESS,
                false, false, List.of("BUDGET"), List.of(), List.of(
                new ProjectPanoramaResult.ModuleResult(
                        "CONTRACT", true, org.example.ai.agent.business.model.DatasetExecutionStatus.SUCCESS,
                        true, "snapshot-contract", execution("CONTRACT", Map.of(
                        "display", Map.of("amount", 8600), "model", Map.of("summary", "合同正常")
                ))),
                new ProjectPanoramaResult.ModuleResult(
                        "BUDGET", true, org.example.ai.agent.business.model.DatasetExecutionStatus.DENIED,
                        false, null, null
                )
        ));
        when(fixture.panoramaExecutionService.execute(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<org.example.ai.agent.business.panorama.model.ProjectPanoramaProgressEvent>
                    progress = invocation.getArgument(1);
            progress.accept(new org.example.ai.agent.business.panorama.model.ProjectPanoramaProgressEvent(
                    "CONTRACT", org.example.ai.agent.business.model.DatasetExecutionStatus.RUNNING, 1, 2
            ));
            progress.accept(new org.example.ai.agent.business.panorama.model.ProjectPanoramaProgressEvent(
                    "CONTRACT", org.example.ai.agent.business.model.DatasetExecutionStatus.SUCCESS, 1, 2
            ));
            return panorama;
        });
        when(fixture.reportDatasetService.list()).thenReturn(List.of(dataset("CONTRACT"), dataset("BUDGET")));
        when(fixture.selectionTokenService.resolve(
                "project-token", "user-1", "conversation-1", BusinessSubjectType.PROJECT
        )).thenReturn(java.util.Optional.of("project-raw-1"));
        when(fixture.modelService.generate(any())).thenThrow(new IllegalStateException("model down"));
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId("task-1");
        task.setFormat("PDF");
        task.setStatus("PENDING");
        task.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(fixture.reportTaskService.create(any())).thenReturn(task);

        fixture.service.handle(request("查询 P-1001 并导出 PDF"), fixture.stream, "run-1");

        verify(fixture.panoramaExecutionService).execute(any(), any());
        InOrder progressOrder = org.mockito.Mockito.inOrder(fixture.accumulator, fixture.stream);
        progressOrder.verify(fixture.accumulator).setContext(any());
        progressOrder.verify(fixture.stream).sendResponseSnapshot();
        progressOrder.verify(fixture.stream).sendResponseEvent(any());
        progressOrder.verify(fixture.stream).publishResponseBlock(any());
        verify(fixture.reportTaskService).create(any());
        ArgumentCaptor<CompositeReportTaskService.CreateCommand> reportCommand =
                ArgumentCaptor.forClass(CompositeReportTaskService.CreateCommand.class);
        verify(fixture.reportTaskService).create(reportCommand.capture());
        assertThat(reportCommand.getValue().plannedReport().plan().subjectId())
                .isEqualTo("project-raw-1");
        ArgumentCaptor<BusinessSnapshotReferenceValidationService.ValidationCommand> validation =
                ArgumentCaptor.forClass(BusinessSnapshotReferenceValidationService.ValidationCommand.class);
        verify(fixture.snapshotReferenceValidationService).validate(validation.capture());
        assertThat(validation.getValue().subjectId()).isEqualTo("project-raw-1");
        assertThat(validation.getValue().canonicalQuery())
                .containsEntry("projectCode", "P-1001")
                .doesNotContainKey("projectYear");
        verify(fixture.stream).failResponseBlock(any());
        InOrder modelFailureOrder = org.mockito.Mockito.inOrder(fixture.stream);
        modelFailureOrder.verify(fixture.stream).startTextResponse(
                org.mockito.ArgumentMatchers.eq("business_narrative"), any(),
                org.mockito.ArgumentMatchers.eq(90), any()
        );
        modelFailureOrder.verify(fixture.stream).failResponseBlock(any());
        verify(fixture.stream, org.mockito.Mockito.atLeastOnce()).publishResponseBlock(
                org.mockito.ArgumentMatchers.argThat(block -> block instanceof ArtifactBlock artifact
                        && "task-1".equals(artifact.taskId()))
        );
        ArgumentCaptor<String> savedResponse = ArgumentCaptor.forClass(String.class);
        verify(fixture.chatSessionService).saveAssistantMessage(
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("conversation-1"), any(),
                org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.eq("model-1"),
                org.mockito.ArgumentMatchers.eq("CHAT"), savedResponse.capture()
        );
        assertThat(savedResponse.getValue()).contains("\"taskId\":\"task-1\"");
    }

    @Test
    void personFollowUpAndExplicitRefreshShouldUseRegisteredDatasets() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any()))
                .thenReturn(personIntent(false))
                .thenReturn(personIntent(true));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(new SubjectResolutionResult(
                SubjectResolutionState.RESOLVED,
                new SubjectCandidate(
                        BusinessSubjectType.PERSON, "selection-token", "张三",
                        "10***01", "工程部", null, null
                ),
                List.of(), 1, 20, 1, false, "主体已定位"
        ));
        when(fixture.reportDatasetService.list()).thenReturn(personDatasets());
        PersonBusinessQueryService.Result result = new PersonBusinessQueryService.Result(
                new PersonBusinessQueryService.TravelSummary(
                        PersonBusinessQueryService.Metric.complete(6),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("1200"))
                ),
                new PersonBusinessQueryService.ReimbursementSummary(
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("900")),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("800")),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("700"))
                ),
                List.of(),
                java.util.Arrays.stream(PersonBusinessQueryService.DatasetType.values())
                        .map(type -> new PersonBusinessQueryService.ModuleResult(
                                type, "PERSON_" + type.name(),
                                PersonBusinessQueryService.ModuleStatus.REUSED, true,
                                "snapshot-" + type.name(), "d".repeat(64)
                        )).toList()
        );
        when(fixture.personBusinessQueryService.query(any())).thenReturn(result);
        when(fixture.modelService.generate(any())).thenReturn("统计完成");
        AgentRequest request = request("上面的出差和打卡情况");
        request.setInheritedInput(Map.of(
                "selectionToken", "selection-token", "subjectType", "PERSON"
        ));

        fixture.service.handle(request, fixture.stream, "run-1");
        fixture.service.handle(request, fixture.stream, "run-2");

        ArgumentCaptor<PersonBusinessQueryService.Command> command =
                ArgumentCaptor.forClass(PersonBusinessQueryService.Command.class);
        verify(fixture.personBusinessQueryService, org.mockito.Mockito.times(2)).query(command.capture());
        assertThat(command.getAllValues())
                .extracting(PersonBusinessQueryService.Command::refreshRequested)
                .containsExactly(false, true);
        assertThat(command.getAllValues().get(0).plans())
                .extracting(PersonBusinessQueryService.DatasetPlan::datasetCode)
                .containsExactly(
                        "CFG_TRAVEL", "CFG_PUNCH", "CFG_LEAVE", "CFG_SCHEDULE",
                        "CFG_CALENDAR", "CFG_REIMBURSEMENT"
                );
        verify(fixture.modelService, never()).generate(any());
    }

    @Test
    void personPdfShouldCreateReportWithRawSubjectAndSafeTerminalSections() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                BusinessSubjectType.PERSON, null, "张三", null,
                null, java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31),
                List.of(), false, "PDF", false
        ));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedPerson());
        when(fixture.reportDatasetService.list()).thenReturn(personDatasets());
        when(fixture.personBusinessQueryService.query(any())).thenReturn(personReportResult());
        when(fixture.selectionTokenService.resolve(
                "selection-token", "user-1", "conversation-1", BusinessSubjectType.PERSON
        )).thenReturn(java.util.Optional.of("employee-raw-1"));
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId("person-report-task");
        task.setFormat("PDF");
        task.setStatus("PENDING");
        task.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        when(fixture.reportTaskService.create(any())).thenReturn(task);

        fixture.service.handle(request("查询张三并导出 PDF"), fixture.stream, "run-1");

        ArgumentCaptor<CompositeReportTaskService.CreateCommand> command =
                ArgumentCaptor.forClass(CompositeReportTaskService.CreateCommand.class);
        verify(fixture.reportTaskService).create(command.capture());
        assertThat(command.getValue().plannedReport().plan().subjectId())
                .isEqualTo("employee-raw-1");
        assertThat(command.getValue().plannedReport().plan().dataComplete()).isFalse();
        assertThat(command.getValue().plannedReport().plan().sections())
                .extracting(
                        org.example.ai.agent.business.report.BusinessReportPlanService.LogicalReportSection::status
                )
                .containsExactly("REUSED", "DENIED", "FAILED", "REUSED", "REUSED", "REUSED");
        ArgumentCaptor<BusinessSnapshotReferenceValidationService.ValidationCommand> validation =
                ArgumentCaptor.forClass(BusinessSnapshotReferenceValidationService.ValidationCommand.class);
        verify(fixture.snapshotReferenceValidationService, org.mockito.Mockito.times(4))
                .validate(validation.capture());
        assertThat(validation.getAllValues())
                .allSatisfy(value -> assertThat(value.canonicalQuery())
                        .containsEntry("employeeNo", "employee-raw-1"));
    }

    @Test
    void reimbursementOnlyShouldAnswerAndReportOneSelectedDataset() throws Exception {
        Fixture fixture = personFixture(personIntent(List.of("REIMBURSEMENT"), "PDF"),
                personResult(List.of(PersonBusinessQueryService.DatasetType.REIMBURSEMENT), Set.of()));
        when(fixture.selectionTokenService.resolve(
                "selection-token", "user-1", "conversation-1", BusinessSubjectType.PERSON
        )).thenReturn(java.util.Optional.of("employee-raw-1"));
        when(fixture.reportTaskService.create(any())).thenReturn(reportTask("person-report-task", "PDF"));

        fixture.service.handle(request("查询报销并导出 PDF"), fixture.stream, "run-1");

        PersonBusinessQueryService.Command query = capturedPersonCommand(fixture);
        assertThat(query.plans()).singleElement().satisfies(plan -> {
            assertThat(plan.type()).isEqualTo(PersonBusinessQueryService.DatasetType.REIMBURSEMENT);
            assertThat(plan.userRequested()).isTrue();
        });
        DeterministicBusinessAnswerComposer.ComposeCommand answer = composedDepartment(fixture);
        assertThat(answer.datasets()).extracting(DeterministicBusinessAnswerComposer.DatasetAnswerInput::datasetCode)
                .containsExactly("CFG_REIMBURSEMENT");
        assertThat(answer.metricDefinitions()).hasSize(3);
        assertThat(answer.tableDefinitions()).isEmpty();
        ArgumentCaptor<BusinessAssistantReportService.PersonReportCommand> report =
                ArgumentCaptor.forClass(BusinessAssistantReportService.PersonReportCommand.class);
        verify(fixture.reportService).createPersonReport(report.capture());
        assertThat(report.getValue().modules()).singleElement()
                .extracting(PersonBusinessQueryService.ModuleResult::type)
                .isEqualTo(PersonBusinessQueryService.DatasetType.REIMBURSEMENT);
    }

    @Test
    void travelOnlyShouldAnswerTwoMetricsWithoutAttendanceTable() throws Exception {
        Fixture fixture = personFixture(personIntent(List.of("TRAVEL"), "PDF"),
                personResult(List.of(PersonBusinessQueryService.DatasetType.TRAVEL), Set.of()));
        allowPersonReport(fixture);

        fixture.service.handle(request("查询出差并导出 PDF"), fixture.stream, "run-1");

        assertThat(capturedPersonCommand(fixture).plans())
                .extracting(PersonBusinessQueryService.DatasetPlan::type)
                .containsExactly(PersonBusinessQueryService.DatasetType.TRAVEL);
        DeterministicBusinessAnswerComposer.ComposeCommand answer = composedDepartment(fixture);
        assertThat(answer.datasets()).extracting(DeterministicBusinessAnswerComposer.DatasetAnswerInput::datasetCode)
                .containsExactly("CFG_TRAVEL");
        assertThat(answer.metricDefinitions()).hasSize(2);
        assertThat(answer.tableDefinitions()).isEmpty();
        ArgumentCaptor<BusinessAssistantReportService.PersonReportCommand> report =
                ArgumentCaptor.forClass(BusinessAssistantReportService.PersonReportCommand.class);
        verify(fixture.reportService).createPersonReport(report.capture());
        assertThat(report.getValue().modules()).singleElement()
                .extracting(PersonBusinessQueryService.ModuleResult::type)
                .isEqualTo(PersonBusinessQueryService.DatasetType.TRAVEL);
    }

    @Test
    void attendanceShouldExecuteFiveDependenciesButPublishOnlyReconciledAttendance() throws Exception {
        List<PersonBusinessQueryService.DatasetType> attendanceTypes = List.of(
                PersonBusinessQueryService.DatasetType.TRAVEL,
                PersonBusinessQueryService.DatasetType.PUNCH,
                PersonBusinessQueryService.DatasetType.LEAVE,
                PersonBusinessQueryService.DatasetType.SCHEDULE,
                PersonBusinessQueryService.DatasetType.CALENDAR
        );
        Fixture fixture = personFixture(personIntent(List.of("ATTENDANCE"), "PDF"),
                personResult(attendanceTypes, Set.of(PersonBusinessQueryService.DatasetType.LEAVE)));
        allowPersonReport(fixture);

        fixture.service.handle(request("查询考勤并导出 PDF"), fixture.stream, "run-1");

        PersonBusinessQueryService.Command query = capturedPersonCommand(fixture);
        assertThat(query.plans()).extracting(PersonBusinessQueryService.DatasetPlan::type)
                .containsExactlyElementsOf(attendanceTypes);
        assertThat(query.plans()).filteredOn(PersonBusinessQueryService.DatasetPlan::userRequested)
                .extracting(PersonBusinessQueryService.DatasetPlan::type)
                .containsExactly(PersonBusinessQueryService.DatasetType.PUNCH);
        DeterministicBusinessAnswerComposer.ComposeCommand answer = composedDepartment(fixture);
        assertThat(answer.datasets()).singleElement().satisfies(dataset -> {
            assertThat(dataset.datasetCode()).isEqualTo("CFG_PUNCH");
            assertThat(dataset.dataComplete()).isFalse();
        });
        assertThat(answer.metricDefinitions()).isEmpty();
        assertThat(answer.tableDefinitions()).singleElement()
                .extracting(DeterministicBusinessAnswerComposer.TableDefinition::datasetCode)
                .isEqualTo("CFG_PUNCH");
        ArgumentCaptor<BusinessAssistantReportService.PersonReportCommand> report =
                ArgumentCaptor.forClass(BusinessAssistantReportService.PersonReportCommand.class);
        verify(fixture.reportService).createPersonReport(report.capture());
        assertThat(report.getValue().modules()).extracting(PersonBusinessQueryService.ModuleResult::type)
                .containsExactlyElementsOf(attendanceTypes);
    }

    @Test
    void attendanceAndTravelShouldPublishTravelOnceWithoutDuplicatingExecution() throws Exception {
        List<PersonBusinessQueryService.DatasetType> attendanceTypes = List.of(
                PersonBusinessQueryService.DatasetType.TRAVEL,
                PersonBusinessQueryService.DatasetType.PUNCH,
                PersonBusinessQueryService.DatasetType.LEAVE,
                PersonBusinessQueryService.DatasetType.SCHEDULE,
                PersonBusinessQueryService.DatasetType.CALENDAR
        );
        Fixture fixture = personFixture(personIntent(List.of("ATTENDANCE", "TRAVEL"), null),
                personResult(attendanceTypes, Set.of()));

        fixture.service.handle(request("查询考勤和出差"), fixture.stream, "run-1");

        PersonBusinessQueryService.Command query = capturedPersonCommand(fixture);
        assertThat(query.plans()).filteredOn(plan -> plan.type() == PersonBusinessQueryService.DatasetType.TRAVEL)
                .hasSize(1).allMatch(PersonBusinessQueryService.DatasetPlan::userRequested);
        DeterministicBusinessAnswerComposer.ComposeCommand answer = composedDepartment(fixture);
        assertThat(answer.datasets()).extracting(DeterministicBusinessAnswerComposer.DatasetAnswerInput::datasetCode)
                .containsExactly("CFG_TRAVEL", "CFG_PUNCH");
        assertThat(answer.metricDefinitions()).hasSize(2);
        assertThat(answer.tableDefinitions()).hasSize(1);
    }

    @Test
    void personSemanticCodesShouldBeSavedAndInheritedFromTrustedState() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any()))
                .thenReturn(personIntent(List.of("PUNCH"), null))
                .thenReturn(personIntent(List.of(), null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedPerson());
        when(fixture.reportDatasetService.list()).thenReturn(personDatasets());
        List<PersonBusinessQueryService.DatasetType> attendanceTypes = List.of(
                PersonBusinessQueryService.DatasetType.TRAVEL,
                PersonBusinessQueryService.DatasetType.PUNCH,
                PersonBusinessQueryService.DatasetType.LEAVE,
                PersonBusinessQueryService.DatasetType.SCHEDULE,
                PersonBusinessQueryService.DatasetType.CALENDAR
        );
        when(fixture.personBusinessQueryService.query(any()))
                .thenReturn(personResult(attendanceTypes, Set.of()));

        fixture.service.handle(request("查询打卡"), fixture.stream, "run-1");
        ArgumentCaptor<org.example.ai.agent.chat.memory.model.BusinessConversationState> state =
                ArgumentCaptor.forClass(org.example.ai.agent.chat.memory.model.BusinessConversationState.class);
        verify(fixture.conversationStateService).saveState(any(), any(), state.capture());
        assertThat(state.getValue().getLastInput()).containsEntry("datasetCodes", List.of("ATTENDANCE"));
        AgentRequest followUp = request("再看一下");
        followUp.setInheritedInput(state.getValue().getLastInput());

        fixture.service.handle(followUp, fixture.stream, "run-2");

        ArgumentCaptor<PersonBusinessQueryService.Command> commands =
                ArgumentCaptor.forClass(PersonBusinessQueryService.Command.class);
        verify(fixture.personBusinessQueryService, org.mockito.Mockito.times(2)).query(commands.capture());
        assertThat(commands.getAllValues()).allSatisfy(command -> assertThat(command.plans()).hasSize(5));
    }

    @Test
    void explicitPersonSemanticsShouldOverrideInheritedAndResetShouldClearThem() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any()))
                .thenReturn(personIntent(List.of("TRAVEL"), null))
                .thenReturn(personIntent(List.of(), null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedPerson());
        when(fixture.reportDatasetService.list()).thenReturn(personDatasets());
        when(fixture.personBusinessQueryService.query(any()))
                .thenReturn(personResult(List.of(PersonBusinessQueryService.DatasetType.TRAVEL), Set.of()))
                .thenReturn(personResult(List.of(PersonBusinessQueryService.DatasetType.values()), Set.of()));
        Map<String, Object> inherited = Map.of(
                "selectionToken", "selection-token",
                "subjectType", "PERSON",
                "datasetCodes", List.of("REIMBURSEMENT")
        );
        AgentRequest override = request("改查出差");
        override.setInheritedInput(inherited);

        fixture.service.handle(override, fixture.stream, "run-1");

        AgentRequest reset = request("重新查询");
        reset.setInheritedInput(inherited);
        reset.setContextReset(true);
        fixture.service.handle(reset, fixture.stream, "run-2");

        ArgumentCaptor<PersonBusinessQueryService.Command> commands =
                ArgumentCaptor.forClass(PersonBusinessQueryService.Command.class);
        verify(fixture.personBusinessQueryService, org.mockito.Mockito.times(2)).query(commands.capture());
        assertThat(commands.getAllValues().get(0).plans())
                .extracting(PersonBusinessQueryService.DatasetPlan::type)
                .containsExactly(PersonBusinessQueryService.DatasetType.TRAVEL);
        assertThat(commands.getAllValues().get(1).plans()).hasSize(6);
    }

    @Test
    void projectDatasetCodesShouldNotUsePersonSelectionOrItsLimit() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                BusinessSubjectType.PROJECT, "P-1001", null, null, 2026, null, null,
                List.of("CONTRACT", "BUDGET", "CASH_FLOW", "OUTPUT", "RISK", "SCHEDULE"),
                false, null, false
        ));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedProject());
        when(fixture.panoramaExecutionService.execute(any(), any())).thenReturn(projectPanorama());

        fixture.service.handle(request("查询项目多个模块"), fixture.stream, "run-1");

        verify(fixture.personDatasetSelectionService, never()).select(any());
        verify(fixture.panoramaExecutionService).execute(any(), any());
    }

    @Test
    void unsupportedPersonSemanticsShouldFailBeforeBusinessExecution() {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(personIntent(List.of("LEAVE"), null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedPerson());

        assertThatThrownBy(() -> fixture.service.handle(request("查询请假"), fixture.stream, "run-1"))
                .hasMessage("暂不支持该人员业务数据类型，请查询出差、考勤或报销");
        verify(fixture.personBusinessQueryService, never()).query(any());
    }

    @Test
    void inheritedPersonSemanticsMustStillPassServerSelectionValidation() {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(personIntent(List.of(), null));
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedPerson());
        AgentRequest request = request("继续查询");
        request.setInheritedInput(Map.of(
                "selectionToken", "selection-token",
                "subjectType", "PERSON",
                "datasetCodes", List.of("LEAVE")
        ));

        assertThatThrownBy(() -> fixture.service.handle(request, fixture.stream, "run-1"))
                .hasMessage("暂不支持该人员业务数据类型，请查询出差、考勤或报销");
        verify(fixture.personBusinessQueryService, never()).query(any());
    }

    @Test
    void clientExtraAndPageContextMustNotChoosePersonDatasets() throws Exception {
        Fixture fixture = personFixture(personIntent(List.of("TRAVEL"), null),
                personResult(List.of(PersonBusinessQueryService.DatasetType.TRAVEL), Set.of()));
        AgentRequest request = request("查询出差");
        request.setExtra(Map.of("datasetCodes", List.of("REIMBURSEMENT")));
        request.setPageContext(Map.of("datasetCodes", List.of("ATTENDANCE")));

        fixture.service.handle(request, fixture.stream, "run-1");

        assertThat(capturedPersonCommand(fixture).plans())
                .extracting(PersonBusinessQueryService.DatasetPlan::type)
                .containsExactly(PersonBusinessQueryService.DatasetType.TRAVEL);
    }

    @Test
    void clientExtraSubjectTypeMustNotBecomeTrustedSubject() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                null, null, null, null, null, null, null, List.of(), false, null, false
        ));
        AgentRequest request = request("查一下相关情况");
        request.setExtra(Map.of("subjectType", "PERSON"));

        fixture.service.handle(request, fixture.stream, "run-1");

        verify(fixture.subjectResolutionService, never()).resolve(any());
        verify(fixture.personBusinessQueryService, never()).query(any());
        verify(fixture.stream).finishChatResponse();
    }

    private SubjectResolutionResult resolvedProject() {
        return new SubjectResolutionResult(
                SubjectResolutionState.RESOLVED,
                new SubjectCandidate(
                        BusinessSubjectType.PROJECT, "project-token", "示例项目",
                        null, null, "P-1001", "DELIVERY"
                ),
                List.of(), 1, 20, 1, false, "主体已定位"
        );
    }

    private SubjectResolutionResult resolvedPerson() {
        return new SubjectResolutionResult(
                SubjectResolutionState.RESOLVED,
                new SubjectCandidate(
                        BusinessSubjectType.PERSON, "selection-token", "张三",
                        "10***01", "工程部", null, null
                ),
                List.of(), 1, 20, 1, false, "主体已定位"
        );
    }

    private BusinessQueryIntent projectIntent(boolean refresh, String projectCode) {
        return new BusinessQueryIntent(
                BusinessSubjectType.PROJECT, projectCode, null, null,
                2026, null, null, List.of(), refresh, null, false
        );
    }

    private ProjectPanoramaResult projectPanorama() {
        return new ProjectPanoramaResult(
                1L, "a".repeat(64), "panorama-1", PanoramaExecutionState.COMPLETE,
                true, true, List.of(), List.of(), List.of(
                new ProjectPanoramaResult.ModuleResult(
                        "CONTRACT", true,
                        org.example.ai.agent.business.model.DatasetExecutionStatus.SUCCESS,
                        true, "snapshot-contract", null,
                        Map.of("amount", 8600), Map.of("summary", "合同正常"), "d".repeat(64)
                )
        ));
    }

    private PersonBusinessQueryService.Result personReportResult() {
        List<PersonBusinessQueryService.ModuleResult> modules = List.of(
                personModule(PersonBusinessQueryService.DatasetType.TRAVEL,
                        PersonBusinessQueryService.ModuleStatus.REUSED),
                failedPersonModule(PersonBusinessQueryService.DatasetType.PUNCH,
                        PersonBusinessQueryService.ModuleStatus.DENIED),
                failedPersonModule(PersonBusinessQueryService.DatasetType.LEAVE,
                        PersonBusinessQueryService.ModuleStatus.FAILED),
                personModule(PersonBusinessQueryService.DatasetType.SCHEDULE,
                        PersonBusinessQueryService.ModuleStatus.REUSED),
                personModule(PersonBusinessQueryService.DatasetType.CALENDAR,
                        PersonBusinessQueryService.ModuleStatus.REUSED),
                personModule(PersonBusinessQueryService.DatasetType.REIMBURSEMENT,
                        PersonBusinessQueryService.ModuleStatus.REUSED)
        );
        return new PersonBusinessQueryService.Result(
                new PersonBusinessQueryService.TravelSummary(
                        PersonBusinessQueryService.Metric.complete(6),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("1200"))
                ),
                new PersonBusinessQueryService.ReimbursementSummary(
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("900")),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("800")),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("700"))
                ),
                List.of(), modules
        );
    }

    private PersonBusinessQueryService.ModuleResult personModule(
            PersonBusinessQueryService.DatasetType type,
            PersonBusinessQueryService.ModuleStatus status) {
        return new PersonBusinessQueryService.ModuleResult(
                type, "CFG_" + type.name(), status, true,
                "snapshot-" + type.name(), "d".repeat(64)
        );
    }

    private PersonBusinessQueryService.ModuleResult failedPersonModule(
            PersonBusinessQueryService.DatasetType type,
            PersonBusinessQueryService.ModuleStatus status) {
        return new PersonBusinessQueryService.ModuleResult(
                type, "CFG_" + type.name(), status, false, null, null
        );
    }

    private DatasetExecutionResult execution(String datasetCode, Map<String, Object> facts) {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        "user-1", "conversation-1", BusinessSubjectType.PROJECT, "project-1",
                        datasetCode, "b".repeat(64), "WF_" + datasetCode, 1L,
                        "c".repeat(64), "d".repeat(64)
                ),
                org.example.ai.agent.business.model.DatasetExecutionStatus.SUCCESS,
                true, facts, "workflow-run", null, null, "查询完成", "proof"
        );
    }

    private BusinessQueryIntent personIntent(boolean refresh) {
        return new BusinessQueryIntent(
                BusinessSubjectType.PERSON, null, null, null,
                null, java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31),
                List.of(), refresh, null, false
        );
    }

    private BusinessQueryIntent personIntent(List<String> datasetCodes, String exportFormat) {
        return new BusinessQueryIntent(
                BusinessSubjectType.PERSON, null, null, null,
                null, java.time.LocalDate.of(2026, 8, 1), java.time.LocalDate.of(2026, 8, 31),
                datasetCodes, false, exportFormat, false
        );
    }

    private Fixture personFixture(
            BusinessQueryIntent intent,
            PersonBusinessQueryService.Result result) {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(intent);
        when(fixture.subjectResolutionService.resolve(any())).thenReturn(resolvedPerson());
        when(fixture.reportDatasetService.list()).thenReturn(personDatasets());
        when(fixture.personBusinessQueryService.query(any())).thenReturn(result);
        return fixture;
    }

    private PersonBusinessQueryService.Command capturedPersonCommand(Fixture fixture) {
        ArgumentCaptor<PersonBusinessQueryService.Command> command =
                ArgumentCaptor.forClass(PersonBusinessQueryService.Command.class);
        verify(fixture.personBusinessQueryService).query(command.capture());
        return command.getValue();
    }

    private PersonBusinessQueryService.Result personResult(
            List<PersonBusinessQueryService.DatasetType> types,
            Set<PersonBusinessQueryService.DatasetType> incompleteTypes) {
        List<PersonBusinessQueryService.ModuleResult> modules = types.stream()
                .map(type -> incompleteTypes.contains(type)
                        ? failedPersonModule(type, PersonBusinessQueryService.ModuleStatus.FAILED)
                        : personModule(type, PersonBusinessQueryService.ModuleStatus.REUSED))
                .toList();
        return new PersonBusinessQueryService.Result(
                new PersonBusinessQueryService.TravelSummary(
                        PersonBusinessQueryService.Metric.complete(6),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("1200"))
                ),
                new PersonBusinessQueryService.ReimbursementSummary(
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("900")),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("800")),
                        PersonBusinessQueryService.Metric.complete(new java.math.BigDecimal("700"))
                ),
                List.of(), modules
        );
    }

    private CompositeReportTask reportTask(String taskId, String format) {
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId(taskId);
        task.setFormat(format);
        task.setStatus("PENDING");
        task.setExpiresAt(java.time.LocalDateTime.now().plusHours(1));
        return task;
    }

    private void allowPersonReport(Fixture fixture) {
        when(fixture.selectionTokenService.resolve(
                "selection-token", "user-1", "conversation-1", BusinessSubjectType.PERSON
        )).thenReturn(java.util.Optional.of("employee-raw-1"));
        when(fixture.reportTaskService.create(any())).thenReturn(reportTask("person-report-task", "PDF"));
    }

    private List<ReportDataset> personDatasets() {
        return java.util.Arrays.stream(PersonBusinessQueryService.DatasetType.values())
                .map(type -> {
                    ReportDataset dataset = new ReportDataset();
                    dataset.setDatasetCode("CFG_" + type.name());
                    dataset.setDatasetName(type.name());
                    dataset.setDomainCode("PERSON_" + type.name());
                    dataset.setSubjectTypesJson("[\"PERSON\"]");
                    dataset.setFieldPolicyChecksum("d".repeat(64));
                    dataset.setEnabled(true);
                    return dataset;
                }).toList();
    }

    private ReportDataset dataset(String code) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode(code);
        dataset.setDatasetName(code);
        dataset.setDomainCode(code);
        dataset.setSubjectTypesJson("[\"PROJECT\"]");
        dataset.setFieldPolicyChecksum("d".repeat(64));
        dataset.setEnabled(true);
        return dataset;
    }

    private AgentRequest request(String question) {
        AgentRequest request = new AgentRequest();
        request.setConversationId("conversation-1");
        request.setUserId("user-1");
        request.setUserQuestion(question);
        request.setAuthorization("Bearer current-user");
        request.setModelCode("model-1");
        return request;
    }

    private static final class Fixture {
        private final BusinessQueryIntentResolver intentResolver = mock(BusinessQueryIntentResolver.class);
        private final SubjectResolutionService subjectResolutionService = mock(SubjectResolutionService.class);
        private final SubjectSelectionTokenService selectionTokenService = mock(SubjectSelectionTokenService.class);
        private final ProjectPanoramaExecutionService panoramaExecutionService = mock(ProjectPanoramaExecutionService.class);
        private final ProjectPanoramaSnapshotReuseService panoramaSnapshotReuseService =
                mock(ProjectPanoramaSnapshotReuseService.class);
        private final PersonBusinessQueryService personBusinessQueryService = mock(PersonBusinessQueryService.class);
        private final PersonDatasetSelectionService personDatasetSelectionService =
                spy(new PersonDatasetSelectionService());
        private final DepartmentBusinessQueryService departmentBusinessQueryService = mock(DepartmentBusinessQueryService.class);
        private final ReportDatasetService reportDatasetService = mock(ReportDatasetService.class);
        private final BusinessAnswerModelService modelService = mock(BusinessAnswerModelService.class);
        private final DeterministicBusinessAnswerComposer composer =
                spy(new DeterministicBusinessAnswerComposer(modelService));
        private final CompositeReportTaskService reportTaskService = mock(CompositeReportTaskService.class);
        private final BusinessSnapshotReferenceValidationService snapshotReferenceValidationService =
                mock(BusinessSnapshotReferenceValidationService.class);
        private final BusinessAssistantReportService reportService = spy(new BusinessAssistantReportService(
                selectionTokenService, reportDatasetService, snapshotReferenceValidationService,
                reportTaskService
        ));
        private final ConversationStateService conversationStateService = mock(ConversationStateService.class);
        private final AiChatSessionService chatSessionService = mock(AiChatSessionService.class);
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final AgentStreamSession stream = mock(AgentStreamSession.class);
        private final ChatResponseAccumulator accumulator = spy(new ChatResponseAccumulator(
                new ResponseStreamContext("response-1", "run-1", "conversation-1")
        ));
        private final ResponseStreamEventFactory eventFactory = mock(ResponseStreamEventFactory.class);
        private final BusinessAssistantService service;

        private Fixture() {
            when(stream.getMessageId()).thenReturn("response-1");
            when(stream.getChatResponseAccumulator()).thenReturn(accumulator);
            when(stream.getResponseEventFactory()).thenReturn(eventFactory);
            when(snapshotReferenceValidationService.validate(any())).thenReturn(true);
            try {
                doAnswer(invocation -> {
                    accumulator.completeBlock(invocation.getArgument(0));
                    return null;
                }).when(stream).publishResponseBlock(any());
            } catch (Exception exception) {
                throw new IllegalStateException("测试流发布模拟失败", exception);
            }
            doAnswer(invocation -> {
                accumulator.setDataComplete(invocation.getArgument(0));
                return null;
            }).when(stream).setResponseDataComplete(org.mockito.ArgumentMatchers.anyBoolean());
            service = new org.example.ai.agent.business.impl.BusinessAssistantServiceImpl(
                    intentResolver, subjectResolutionService, panoramaExecutionService,
                    panoramaSnapshotReuseService,
                    personBusinessQueryService, personDatasetSelectionService,
                    departmentBusinessQueryService, reportDatasetService, composer,
                    reportService, conversationStateService, chatSessionService, objectMapper
            );
        }
    }
}
