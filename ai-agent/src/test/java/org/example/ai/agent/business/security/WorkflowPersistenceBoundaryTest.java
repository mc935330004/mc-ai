package org.example.ai.agent.business.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.access.service.AgentResourceAccessService;
import org.example.ai.agent.answer.extractor.DictionaryFactExtractor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpInvoker;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpRequest;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpRequestBuilder;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationContext;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationContextFactory;
import org.example.ai.agent.capability.invocation.runtime.CapabilityResponseInterpreter;
import org.example.ai.agent.capability.invocation.runtime.ResponseInterpretationResult;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.FieldMetadataService;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.GraphNodeStatus;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.common.enums.WorkflowRunStatus;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.graph.runtime.ForEachBatchResult;
import org.example.ai.agent.graph.runtime.ForEachItemResult;
import org.example.ai.agent.graph.runtime.GraphExecutionResult;
import org.example.ai.agent.graph.runtime.GraphNodeResult;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.security.PmCapabilityPermissionVerifier;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.tool.impl.BusinessCapabilityExecutorImpl;
import org.example.ai.agent.tool.projection.CapabilityFieldPathProjector;
import org.example.ai.agent.tool.projection.CapabilityOutputProjector;
import org.example.ai.agent.trace.mapper.RunStepMapper;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContext;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldPolicy;
import org.example.ai.agent.workflow.answer.WorkflowAnswerModelPayload;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPayloadFactory;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactService;
import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifact;
import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifactChunk;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactChunkMapper;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactMapper;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkConsumer;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlanner;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.entity.WorkflowRunItem;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunItemMapper;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunMapper;
import org.example.ai.agent.workflow.run.service.impl.WorkflowRunServiceImpl;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcomeFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.ApplicationEventPublisher;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 证明普通工作流只持久化、记录并发送字段字典投影后的安全结果。
 */
class WorkflowPersistenceBoundaryTest {

    private static final String SENTINEL = "MUST_NOT_PERSIST";
    private static final String RUN_ID = "run-safe-persistence";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void rawWorkflowResponseNeverCrossesPersistenceLogOrModelBoundary() {
        Logger workflowLogger = (Logger) LoggerFactory.getLogger("org.example.ai.agent");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        workflowLogger.addAppender(appender);
        try {
            ToolResult projected = executeRawCapabilityResponse();

            assertThat(objectMapper.valueToTree(projected.getDisplayData()).toString())
                    .contains("expense", "approvedAmount", "12580")
                    .doesNotContain(SENTINEL);
            assertThat(projected.getRaw()).isNull();

            WorkflowExecutionOutcome outcome = createOutcome(projected.getDisplayData());
            persistWorkflowRun(outcome);

            WorkflowAnswerFieldPolicy fieldPolicy = approvedAmountPolicy();
            WorkflowAnswerModelPayload modelPayload = new WorkflowAnswerPayloadFactory(objectMapper)
                    .create(outcome, fieldPolicy.modelFieldNames());
            WorkflowAnswerChunkPlan chunkPlan = new WorkflowAnswerChunkPlanner(objectMapper, 12_000, 10)
                    .plan(modelPayload);

            persistArtifact(outcome, fieldPolicy, chunkPlan);
            consumeWithModel(chunkPlan);
        } finally {
            workflowLogger.detachAppender(appender);
            appender.stop();
        }

        /*
         * consumer 首次调用失败会产生一条 WARN，
         * 同时证明包级 appender 确实覆盖了整条工作流链路。
         */
        assertThat(appender.list)
                .isNotEmpty()
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain(SENTINEL));
    }

    private ToolResult executeRawCapabilityResponse() {
        CapabilityDefinitionService definitionService = mock(CapabilityDefinitionService.class);
        AgentResourceAccessService accessService = mock(AgentResourceAccessService.class);
        CapabilityInvocationContextFactory contextFactory = mock(CapabilityInvocationContextFactory.class);
        CapabilityHttpRequestBuilder requestBuilder = mock(CapabilityHttpRequestBuilder.class);
        CapabilityHttpInvoker httpInvoker = mock(CapabilityHttpInvoker.class);
        CapabilityResponseInterpreter responseInterpreter = mock(CapabilityResponseInterpreter.class);
        FieldMetadataService fieldMetadataService = mock(FieldMetadataService.class);

        CapabilityDefinition capability = new CapabilityDefinition();
        capability.setCapabilityCode("expense.approval.query");
        capability.setCapabilityName("费用审批查询");
        capability.setSideEffect("READ");
        when(definitionService.getEnabledByCode("expense.approval.query")).thenReturn(capability);

        CapabilityInvocationContext invocationContext = mock(CapabilityInvocationContext.class);
        when(contextFactory.create(any(), any())).thenReturn(invocationContext);
        CapabilityHttpRequest httpRequest = CapabilityHttpRequest.builder()
                .uri(URI.create("https://pm.example.test/expenses"))
                .auditInput(Map.of("projectCode", "P-1001"))
                .build();
        when(requestBuilder.build(any(), any(), any())).thenReturn(httpRequest);

        Map<String, Object> rawResponse = Map.of(
                "data", Map.of(
                        "expense", List.of(Map.of(
                                "approvedAmount", 12_580,
                                "secretToken", SENTINEL
                        ))
                )
        );
        when(httpInvoker.invoke(httpRequest)).thenReturn(rawResponse);
        when(responseInterpreter.interpret(capability, rawResponse, false))
                .thenReturn(ResponseInterpretationResult.success(
                        "200",
                        "OK",
                        rawResponse.get("data"),
                        false
                ));
        when(fieldMetadataService.loadPublished("expense.approval.query"))
                .thenReturn(List.of(FieldMeta.builder()
                        .name("approvedAmount")
                        .cnName("approvedAmount")
                        .path("$.data.expense[].approvedAmount")
                        .modelVisible(1)
                        .userVisible(1)
                        .build()));

        CapabilityOutputProjector projector = new CapabilityOutputProjector(
                objectMapper,
                new CapabilityFieldPathProjector(objectMapper)
        );
        BusinessCapabilityExecutorImpl executor = new BusinessCapabilityExecutorImpl(
                definitionService,
                accessService,
                contextFactory,
                requestBuilder,
                httpInvoker,
                responseInterpreter,
                fieldMetadataService,
                mock(PmCapabilityPermissionVerifier.class),
                mock(DictionaryFactExtractor.class),
                projector
        );

        return executor.execute(
                ToolExecutionContext.builder().runId(RUN_ID).userId("user-1").build(),
                PlanStep.builder()
                        .capabilityCode("expense.approval.query")
                        .outputKey("expense")
                        .input(Map.of("projectCode", "P-1001"))
                        .build()
        );
    }

    private WorkflowExecutionOutcome createOutcome(Object safeResult) {
        ForEachItemResult item = ForEachItemResult.success(
                0,
                Map.of("expenseId", "E-1"),
                safeResult,
                5L
        );
        ForEachBatchResult batch = ForEachBatchResult.from(List.of(item));
        GraphNodeResult foreachNode = new GraphNodeResult(
                "expense-loop",
                GraphNodeType.FOREACH,
                GraphNodeStatus.SUCCESS,
                batch,
                batch,
                batch,
                false,
                "200",
                "OK",
                null,
                null,
                "费用审批查询完成",
                Map.of(),
                5L
        );
        GraphExecutionResult graphResult = new GraphExecutionResult(
                true,
                RUN_ID,
                "expense-workflow",
                safeResult,
                null,
                null,
                Map.of("expense-loop", foreachNode),
                Map.of(),
                10L
        );

        return new WorkflowExecutionOutcomeFactory().create(
                RUN_ID,
                "expense-workflow",
                "费用审批工作流",
                1L,
                1,
                graphResult
        );
    }

    private void persistWorkflowRun(WorkflowExecutionOutcome outcome) {
        WorkflowRunMapper runMapper = mock(WorkflowRunMapper.class);
        WorkflowRunItemMapper itemMapper = mock(WorkflowRunItemMapper.class);
        WorkflowRun running = new WorkflowRun();
        running.setRunId(RUN_ID);
        running.setStatus(WorkflowRunStatus.RUNNING.name());
        when(runMapper.selectByRunIdForUpdate(RUN_ID)).thenReturn(running);
        when(runMapper.updateById(any(WorkflowRun.class))).thenReturn(1);
        when(itemMapper.insert(any(WorkflowRunItem.class))).thenReturn(1);

        WorkflowRunServiceImpl service = new WorkflowRunServiceImpl(
                runMapper,
                itemMapper,
                mock(RunStepMapper.class),
                objectMapper,
                mock(ApplicationEventPublisher.class)
        );
        service.complete(RUN_ID, outcome);

        ArgumentCaptor<WorkflowRun> runCaptor = ArgumentCaptor.forClass(WorkflowRun.class);
        verify(runMapper).updateById(runCaptor.capture());
        assertSafeJson(runCaptor.getValue().getResultJson());

        ArgumentCaptor<WorkflowRunItem> itemCaptor = ArgumentCaptor.forClass(WorkflowRunItem.class);
        verify(itemMapper).insert(itemCaptor.capture());
        assertSafeJson(itemCaptor.getValue().getResultJson());
    }

    private void persistArtifact(
            WorkflowExecutionOutcome outcome,
            WorkflowAnswerFieldPolicy fieldPolicy,
            WorkflowAnswerChunkPlan chunkPlan) {
        ResultArtifactMapper artifactMapper = mock(ResultArtifactMapper.class);
        ResultArtifactChunkMapper chunkMapper = mock(ResultArtifactChunkMapper.class);
        when(artifactMapper.insert(any(ResultArtifact.class))).thenReturn(1);
        when(artifactMapper.updateById(any(ResultArtifact.class))).thenReturn(1);
        when(chunkMapper.insert(any(ResultArtifactChunk.class))).thenReturn(1);

        AgentRequest request = request();
        new ResultArtifactService(artifactMapper, chunkMapper, objectMapper, 24)
                .save(request, outcome, fieldPolicy, chunkPlan);

        ArgumentCaptor<ResultArtifactChunk> chunkCaptor = ArgumentCaptor.forClass(ResultArtifactChunk.class);
        verify(chunkMapper, times(chunkPlan.totalChunks())).insert(chunkCaptor.capture());
        assertThat(chunkCaptor.getAllValues())
                .extracting(ResultArtifactChunk::getPayloadJson)
                .allSatisfy(this::assertSafeJson);

        ArgumentCaptor<ResultArtifact> artifactCaptor = ArgumentCaptor.forClass(ResultArtifact.class);
        verify(artifactMapper).insert(artifactCaptor.capture());
        assertThat(artifactCaptor.getValue().getFieldSemanticsJson()).doesNotContain(SENTINEL);
    }

    private void consumeWithModel(WorkflowAnswerChunkPlan chunkPlan) {
        TrackedChatClientService chatClientService = mock(TrackedChatClientService.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn("费用审批金额为 12580 元");
        when(chatClientService.call(any(), any(), any()))
                .thenThrow(new IllegalStateException("临时模型故障"))
                .thenReturn(response);

        ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            new WorkflowAnswerChunkConsumer(chatClientService, executorService)
                    .consume(request(), RUN_ID, "[]", chunkPlan);
        } finally {
            executorService.shutdownNow();
        }

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(chatClientService, times(2)).call(any(), systemPrompt.capture(), userPrompt.capture());
        assertThat(systemPrompt.getAllValues()).allSatisfy(prompt -> assertThat(prompt).doesNotContain(SENTINEL));
        assertThat(userPrompt.getAllValues()).allSatisfy(prompt -> assertSafeJson(prompt));
    }

    private WorkflowAnswerFieldPolicy approvedAmountPolicy() {
        return new WorkflowAnswerFieldPolicy(List.of(new WorkflowAnswerFieldContext(
                1L,
                "expense.approval.query",
                "approvedAmount",
                "审批金额",
                "审批通过的金额",
                "amount",
                "费用",
                "$.data.expense[].approvedAmount",
                "DECIMAL",
                true,
                true,
                "expense.approvedAmount",
                1,
                true,
                "HIGH",
                "NUMBER",
                1,
                "元",
                2,
                "PM",
                null,
                "-"
        )));
    }

    private AgentRequest request() {
        AgentRequest request = new AgentRequest();
        request.setUserId("user-1");
        request.setConversationId("session-1");
        request.setUserQuestion("查询费用审批金额");
        return request;
    }

    private void assertSafeJson(String json) {
        assertThat(json)
                .contains("expense", "approvedAmount", "12580")
                .doesNotContain(SENTINEL);
    }
}
