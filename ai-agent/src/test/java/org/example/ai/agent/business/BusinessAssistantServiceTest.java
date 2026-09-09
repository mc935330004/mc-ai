package org.example.ai.agent.business;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.answer.BusinessAnswerModelService;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.intent.BusinessQueryIntent;
import org.example.ai.agent.business.intent.BusinessQueryIntentResolver;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.panorama.ProjectPanoramaExecutionService;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.CompositeReportTaskService;
import org.example.ai.agent.business.subject.SubjectResolutionService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.stream.ChatResponseAccumulator;
import org.example.ai.agent.chat.stream.ResponseStreamEventFactory;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessAssistantServiceTest {

    @Test
    void projectCandidatesMustNotExecuteBusinessModules() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                BusinessSubjectType.PROJECT, null, null, null,
                2026, null, null, List.of(), false, null
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
                2026, null, null, List.of(), false, "PDF"
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
        verify(fixture.stream).failResponseBlock(any());
        InOrder modelFailureOrder = org.mockito.Mockito.inOrder(fixture.stream);
        modelFailureOrder.verify(fixture.stream).startTextResponse(
                org.mockito.ArgumentMatchers.eq("business_narrative"), any(),
                org.mockito.ArgumentMatchers.eq(90), any()
        );
        modelFailureOrder.verify(fixture.stream).failResponseBlock(any());
        verify(fixture.chatSessionService).saveAssistantMessage(
                org.mockito.ArgumentMatchers.eq("user-1"),
                org.mockito.ArgumentMatchers.eq("conversation-1"), any(),
                org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.eq("model-1"),
                org.mockito.ArgumentMatchers.eq("CHAT"), any()
        );
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
    void clientExtraSubjectTypeMustNotBecomeTrustedSubject() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.intentResolver.resolve(any(), any())).thenReturn(new BusinessQueryIntent(
                null, null, null, null, null, null, null, List.of(), false, null
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
                List.of(), refresh, null
        );
    }

    private List<ReportDataset> personDatasets() {
        return java.util.Arrays.stream(PersonBusinessQueryService.DatasetType.values())
                .map(type -> {
                    ReportDataset dataset = new ReportDataset();
                    dataset.setDatasetCode("CFG_" + type.name());
                    dataset.setDatasetName(type.name());
                    dataset.setDomainCode("PERSON_" + type.name());
                    dataset.setSubjectTypesJson("[\"PERSON\"]");
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
        private final PersonBusinessQueryService personBusinessQueryService = mock(PersonBusinessQueryService.class);
        private final ReportDatasetService reportDatasetService = mock(ReportDatasetService.class);
        private final BusinessAnswerModelService modelService = mock(BusinessAnswerModelService.class);
        private final DeterministicBusinessAnswerComposer composer =
                new DeterministicBusinessAnswerComposer(modelService);
        private final CompositeReportTaskService reportTaskService = mock(CompositeReportTaskService.class);
        private final ConversationStateService conversationStateService = mock(ConversationStateService.class);
        private final AiChatSessionService chatSessionService = mock(AiChatSessionService.class);
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final AgentStreamSession stream = mock(AgentStreamSession.class);
        private final ChatResponseAccumulator accumulator = mock(ChatResponseAccumulator.class);
        private final ResponseStreamEventFactory eventFactory = mock(ResponseStreamEventFactory.class);
        private final BusinessAssistantService service;

        private Fixture() {
            when(stream.getMessageId()).thenReturn("response-1");
            when(stream.getChatResponseAccumulator()).thenReturn(accumulator);
            when(stream.getResponseEventFactory()).thenReturn(eventFactory);
            service = new org.example.ai.agent.business.impl.BusinessAssistantServiceImpl(
                    intentResolver, subjectResolutionService, selectionTokenService, panoramaExecutionService,
                    personBusinessQueryService, reportDatasetService, composer,
                    reportTaskService, conversationStateService, chatSessionService, objectMapper
            );
        }
    }
}
