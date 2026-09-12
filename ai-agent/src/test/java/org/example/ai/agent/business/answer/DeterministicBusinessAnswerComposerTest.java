package org.example.ai.agent.business.answer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.ColumnDefinition;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.ComposeCommand;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.DatasetAnswerInput;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.MetricDefinition;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.TableDefinition;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.model.IssueMatchStatus;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.chat.protocol.block.ArtifactBlock;
import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.block.StatusListBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseContext;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.stream.ChatResponseAccumulator;
import org.example.ai.agent.chat.stream.ResponseChecksumService;
import org.example.ai.agent.chat.stream.ResponseSequenceGenerator;
import org.example.ai.agent.chat.stream.ResponseStreamContext;
import org.example.ai.agent.chat.stream.ResponseStreamEventFactory;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务回答协议和确定性编排的安全契约测试。
 */
class DeterministicBusinessAnswerComposerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void protocolRoundTripsNewBlocksAndKeepsV1HistoryCompatible() throws Exception {
        ResponseContext context = context();
        StatusListBlock status = statusBlock();
        ArtifactBlock artifact = artifact("COMPLETED", BlockStatus.READY);

        String statusJson = objectMapper.writeValueAsString(status);
        String artifactJson = objectMapper.writeValueAsString(artifact);

        assertThat(objectMapper.readValue(statusJson, ResponseBlock.class))
                .isEqualTo(status);
        assertThat(objectMapper.readValue(artifactJson, ResponseBlock.class))
                .isEqualTo(artifact);
        assertThat(context.subjectId()).isEqualTo("P-1001");
        assertThat(status.type()).isEqualTo(BlockType.STATUS_LIST);
        assertThat(artifact.type()).isEqualTo(BlockType.ARTIFACT);

        AiResponse legacy = objectMapper.readValue("""
                {
                  "schemaVersion":1,
                  "responseId":"response-1",
                  "runId":"run-1",
                  "conversationId":"conversation-1",
                  "mode":"CHAT",
                  "status":"COMPLETED",
                  "dataComplete":true,
                  "blocks":[],
                  "references":[],
                  "meta":{}
                }
                """, AiResponse.class);

        assertThat(legacy.schemaVersion()).isEqualTo(1);
        assertThat(legacy.context()).isNull();
    }

    @Test
    void accumulatorUsesChatSchemaV2ContextAndStableChecksum() {
        ChatResponseAccumulator accumulator = new ChatResponseAccumulator(
                new ResponseStreamContext("response-1", "run-1", "conversation-1")
        );
        accumulator.setContext(context());
        accumulator.setDataComplete(true);
        accumulator.completeBlock(statusBlock());

        AiResponse snapshot = accumulator.snapshot();
        ResponseChecksumService checksumService = new ResponseChecksumService(objectMapper);
        String json = checksumService.serialize(snapshot);
        String repeatedJson = checksumService.serialize(snapshot);

        assertThat(snapshot.schemaVersion()).isEqualTo(AiResponse.CURRENT_SCHEMA_VERSION);
        assertThat(snapshot.context()).isEqualTo(context());
        assertThat(checksumService.calculate(json)).hasSize(64);
        assertThat(repeatedJson).isEqualTo(json);
        assertThat(checksumService.calculate(repeatedJson))
                .isEqualTo(checksumService.calculate(json));

        ResponseStreamEventFactory eventFactory = new ResponseStreamEventFactory(
                new ResponseStreamContext("response-1", "run-1", "conversation-1"),
                checksumService,
                new ResponseSequenceGenerator()
        );
        assertThat(eventFactory.blockDone(artifact("PENDING", BlockStatus.PENDING))
                .payload().block().type()).isEqualTo(BlockType.ARTIFACT);
        assertThat(eventFactory.blockDone(statusBlock())
                .payload().block().type()).isEqualTo(BlockType.STATUS_LIST);
        var snapshotEvent = eventFactory.responseSnapshot(snapshot);
        assertThat(snapshotEvent.payload().checksum()).isEqualTo(
                checksumService.calculate(snapshotEvent.payload().documentJson())
        );
    }

    @Test
    void composesDeterministicBlocksInFixedOrderAndLimitsTables() {
        BusinessAnswerModelService modelService = mock(BusinessAnswerModelService.class);
        when(modelService.generate(any())).thenReturn("基于可信事实的说明");
        DeterministicBusinessAnswerComposer composer =
                new DeterministicBusinessAnswerComposer(modelService);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            rows.add(Map.of("name", "成员" + index, "hours", index));
        }
        Map<String, Object> displayFacts = new LinkedHashMap<>();
        displayFacts.put("budget", 1200);
        displayFacts.put("members", rows);
        DatasetAnswerInput dataset = new DatasetAnswerInput(
                "PROJECT", "项目概览", DatasetExecutionStatus.SUCCESS, true,
                displayFacts,
                Map.of("modelSummary", "预算正常"),
                "已完成"
        );

        AiResponse response = composer.compose(command(
                List.of(dataset),
                List.of(new MetricDefinition(
                        "PROJECT", "budget", "预算", ValueType.AMOUNT, "元", Tone.DEFAULT
                )),
                List.of(new TableDefinition(
                        "member_table", "成员", "PROJECT", "members",
                        List.of(
                                new ColumnDefinition("name", "姓名", ValueType.TEXT, "", Tone.DEFAULT),
                                new ColumnDefinition("hours", "工时", ValueType.NUMBER, "小时", Tone.DEFAULT)
                        )
                )),
                List.of(
                        new ProjectIssueResult(
                                "OVER_BUDGET", IssueMatchStatus.MATCHED, "WARNING",
                                "预算使用接近上限", List.of("budget")
                        ),
                        new ProjectIssueResult(
                                "MISSING_PROGRESS", IssueMatchStatus.UNKNOWN, "WARNING",
                                "部分进度事实不足", List.of("progress")
                        )
                ),
                artifact("COMPLETED", BlockStatus.READY),
                true
        ));

        assertThat(response.context()).isEqualTo(context());
        assertThat(response.blocks()).extracting(block -> block.type().name())
                .containsExactly(
                        "STATUS_LIST", "METRICS", "WARNINGS", "CALLOUT",
                        "TABLE", "TEXT", "ARTIFACT"
                );
        assertThat(response.blocks()).extracting(ResponseBlock::order)
                .containsExactly(10, 20, 30, 40, 50, 90, 100);
        assertThat(response.blocks().get(4))
                .extracting("total", "hasMore")
                .containsExactly(12L, true);
        assertThat(response.status()).isEqualTo(ResponseStatus.COMPLETED);
    }

    @Test
    void sendsOnlyModelFactsAndKeepsDeterministicBlocksWhenModelFails() throws Exception {
        BusinessAnswerModelService modelService = mock(BusinessAnswerModelService.class);
        when(modelService.generate(any())).thenThrow(new IllegalStateException("raw model error"));
        DeterministicBusinessAnswerComposer composer =
                new DeterministicBusinessAnswerComposer(modelService);
        DatasetAnswerInput dataset = new DatasetAnswerInput(
                "PROJECT", "项目概览", DatasetExecutionStatus.SUCCESS, true,
                Map.of("displaySecret", "masked-display", "budget", 100),
                Map.of("modelSummary", "允许分析"),
                "已完成"
        );

        AiResponse response = composer.compose(command(
                List.of(dataset),
                List.of(new MetricDefinition(
                        "PROJECT", "budget", "预算", ValueType.AMOUNT, "元", Tone.DEFAULT
                )),
                List.of(), List.of(), null, true
        ));

        ArgumentCaptor<BusinessAnswerModelService.ModelRequest> request =
                ArgumentCaptor.forClass(BusinessAnswerModelService.ModelRequest.class);
        verify(modelService).generate(request.capture());
        String modelJson = objectMapper.writeValueAsString(request.getValue());
        assertThat(modelJson)
                .contains("modelSummary")
                .doesNotContain("displaySecret")
                .doesNotContain("masked-display");
        assertThat(response.blocks()).extracting(block -> block.type().name())
                .containsExactly("STATUS_LIST", "METRICS");
        assertThat(response.status()).isEqualTo(ResponseStatus.PARTIAL);
    }

    @Test
    void skipsModelWhenNarrativeIsNotRequiredAndUsesCompletenessStatus() {
        BusinessAnswerModelService modelService = mock(BusinessAnswerModelService.class);
        DeterministicBusinessAnswerComposer composer =
                new DeterministicBusinessAnswerComposer(modelService);

        AiResponse response = composer.compose(command(
                List.of(new DatasetAnswerInput(
                        "PROJECT", "项目概览", DatasetExecutionStatus.TIMEOUT, false,
                        Map.of(), Map.of("mustNotLeak", "value"), "查询超时"
                )),
                List.of(), List.of(), List.of(), null, false
        ));

        verify(modelService, never()).generate(any());
        assertThat(response.status()).isEqualTo(ResponseStatus.PARTIAL);
        assertThat(response.blocks()).extracting(ResponseBlock::type)
                .containsExactly(BlockType.STATUS_LIST);
    }

    @Test
    void protocolRecordsDoNotExposeForbiddenArtifactFields() throws Exception {
        List<String> forbidden = List.of(
                "storagePath", "downloadUrl", "anonymousUrl", "checksum"
        );
        List<String> artifactComponents = List.of(ArtifactBlock.class.getRecordComponents())
                .stream()
                .map(component -> component.getName())
                .toList();
        List<String> contextComponents = List.of(ResponseContext.class.getRecordComponents())
                .stream()
                .map(component -> component.getName())
                .toList();

        assertThat(artifactComponents).doesNotContainAnyElementsOf(forbidden);
        assertThat(contextComponents).containsExactly(
                "subjectType", "subjectId", "subjectLabel", "scopeLabel",
                "periodStart", "periodEnd", "snapshotAt"
        );
        assertThat(objectMapper.writeValueAsString(artifact("FAILED", BlockStatus.FAILED)))
                .doesNotContain("storagePath", "downloadUrl", "anonymousUrl", "checksum");
        assertThatThrownBy(() -> new StatusListBlock.StatusItem(
                "PROJECT", "项目概览", "FAILED", Tone.DANGER, " "
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void artifactRepresentsAllSafeTaskStates() throws Exception {
        for (String taskStatus : List.of("PENDING", "COMPLETED", "FAILED", "EXPIRED")) {
            ArtifactBlock block = artifact(
                    taskStatus,
                    "PENDING".equals(taskStatus) ? BlockStatus.PENDING
                            : "COMPLETED".equals(taskStatus) ? BlockStatus.READY
                            : BlockStatus.FAILED
            );
            String json = objectMapper.writeValueAsString(block);
            assertThat(objectMapper.readValue(json, ResponseBlock.class))
                    .isEqualTo(block);
        }
    }

    @Test
    void modelServiceUsesTrackedAnswerCallAndRejectsEmptyText() {
        TrackedChatClientService client = mock(TrackedChatClientService.class);
        ChatResponse response = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(response.getResult().getOutput().getText()).thenReturn(" 可信说明 ");
        when(client.call(any(), any(), any())).thenReturn(response);
        BusinessAnswerModelService service = new BusinessAnswerModelService(objectMapper, client);
        BusinessAnswerModelService.ModelRequest request =
                new BusinessAnswerModelService.ModelRequest(
                        "run-1", "conversation-1", "user-1", "model-1", "项目怎么样",
                        List.of(new BusinessAnswerModelService.ModelDatasetInput(
                                "PROJECT", "项目", Map.of("allowedFact", "允许")
                        ))
                );

        assertThat(service.generate(request)).isEqualTo("可信说明");

        ArgumentCaptor<ModelCallContext> context = ArgumentCaptor.forClass(ModelCallContext.class);
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(client).call(context.capture(), systemPrompt.capture(), userPrompt.capture());
        assertThat(context.getValue().getCallType()).isEqualTo(ModelCallType.ANSWER);
        assertThat(userPrompt.getValue()).contains("allowedFact", "允许");
        assertThat(systemPrompt.getValue()).contains("禁止重新计算", "禁止编造");

        when(response.getResult().getOutput().getText()).thenReturn(" ");
        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("为空");
    }

    @Test
    void rejectsUnsafeFactValuesWithoutCallingTheirToString() {
        AtomicInteger toStringCalls = new AtomicInteger();
        Object malicious = new Object() {
            @Override
            public String toString() {
                toStringCalls.incrementAndGet();
                return "SENSITIVE_VALUE";
            }
        };

        assertThatThrownBy(() -> new DatasetAnswerInput(
                "PROJECT", "项目", DatasetExecutionStatus.SUCCESS, true,
                Map.of("unsafe", malicious), Map.of(), "已完成"
        )).isInstanceOf(BusinessException.class);
        assertThat(toStringCalls).hasValue(0);

        Map<Object, Object> nonStringKey = new LinkedHashMap<>();
        nonStringKey.put(1, "value");
        assertThatThrownBy(() -> new BusinessAnswerModelService.ModelDatasetInput(
                "PROJECT", "项目", castMap(nonStringKey)
        )).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new DatasetAnswerInput(
                "PROJECT", "项目", DatasetExecutionStatus.SUCCESS, true,
                Map.of("nested", Map.of(" ", "value")), Map.of(), "已完成"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key不能为空");
    }

    @Test
    void truncatesOversizedModelFactsButStillCompletesModelAnswer() {
        TrackedChatClientService client = mock(TrackedChatClientService.class);
        ChatResponse modelResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(modelResponse.getResult().getOutput().getText()).thenReturn("截断范围内的可信说明");
        when(client.call(any(), any(), any())).thenReturn(modelResponse);
        BusinessAnswerModelService service = new BusinessAnswerModelService(objectMapper, client);
        DeterministicBusinessAnswerComposer composer =
                new DeterministicBusinessAnswerComposer(service);
        Map<String, Object> modelFacts = new LinkedHashMap<>();
        modelFacts.put("oversized", "SECRET_MARKER-" + "x".repeat(40_000));
        modelFacts.put("smallFact", "保留事实");
        DatasetAnswerInput dataset = new DatasetAnswerInput(
                "PROJECT", "项目", DatasetExecutionStatus.SUCCESS, true,
                Map.of(), modelFacts, "已完成"
        );
        ComposeCommand base = command(
                List.of(dataset), List.of(), List.of(), List.of(), null, true
        );
        ComposeCommand oversizedQuestion = new ComposeCommand(
                base.responseId(), base.runId(), base.conversationId(), base.context(),
                base.dataComplete(), base.datasets(), base.metricDefinitions(),
                base.tableDefinitions(), base.issues(), base.artifact(), true,
                "Q".repeat(10_000) + "QUESTION_TAIL_MARKER",
                base.userId(), base.modelCode(), base.references(), base.meta()
        );

        AiResponse response = composer.compose(oversizedQuestion);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).call(any(), any(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("\"modelFactsTruncated\":true", "smallFact", "保留事实")
                .contains("截断只限制本段模型分析范围")
                .doesNotContain("SECRET_MARKER", "QUESTION_TAIL_MARKER");
        assertThat(prompt.getValue().length()).isLessThan(35_000);
        assertThat(response.status()).isEqualTo(ResponseStatus.COMPLETED);
        assertThat(response.blocks()).extracting(ResponseBlock::type)
                .containsExactly(BlockType.STATUS_LIST, BlockType.TEXT);
    }

    @Test
    void artifactRejectsUnknownStatusPathsAndUrls() {
        assertThatThrownBy(() -> artifact("UNKNOWN", BlockStatus.PENDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("taskStatus");

        for (String fileName : List.of(
                "../report.xlsx", "folder/report.xlsx", "folder\\report.xlsx",
                "C:\\report.xlsx", "file:/report.xlsx", "https://example.test/report.xlsx"
        )) {
            assertThatThrownBy(() -> new ArtifactBlock(
                    "artifact", "报告", 1, BlockStatus.READY, BlockSource.SYSTEM,
                    "task-1", "XLSX", fileName, "SUCCESS", null, true, "已完成"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("fileName");
        }
    }

    @Test
    void limitsModelEnvelopeByUtf8BytesAndKeepsLaterFacts() {
        TrackedChatClientService client = mock(TrackedChatClientService.class);
        ChatResponse modelResponse = mock(ChatResponse.class, RETURNS_DEEP_STUBS);
        when(modelResponse.getResult().getOutput().getText()).thenReturn("可信说明");
        when(client.call(any(), any(), any())).thenReturn(modelResponse);
        BusinessAnswerModelService service = new BusinessAnswerModelService(objectMapper, client);
        Map<String, Object> modelFacts = new LinkedHashMap<>();
        modelFacts.put("multibyte", "中".repeat(12_000));
        modelFacts.put("laterFact", "保留");
        BusinessAnswerModelService.ModelRequest request =
                new BusinessAnswerModelService.ModelRequest(
                        "run-1", "conversation-1", "user-1", "model-1", "说明项目",
                        List.of(new BusinessAnswerModelService.ModelDatasetInput(
                                "PROJECT", "项目", modelFacts
                        ))
                );

        assertThat(service.generate(request)).isEqualTo("可信说明");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(client).call(any(), any(), prompt.capture());
        String envelope = extractEnvelope(prompt.getValue());
        assertThat(envelope.getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(32_000);
        assertThat(envelope)
                .contains("\"modelFactsTruncated\":true", "laterFact", "保留")
                .doesNotContain("中中中中中中中中中中");
    }

    @Test
    void rejectsDuplicateTableAndArtifactBlockIds() {
        BusinessAnswerModelService modelService = mock(BusinessAnswerModelService.class);
        DeterministicBusinessAnswerComposer composer =
                new DeterministicBusinessAnswerComposer(modelService);
        DatasetAnswerInput dataset = new DatasetAnswerInput(
                "PROJECT", "项目", DatasetExecutionStatus.SUCCESS, true,
                Map.of(
                        "firstRows", List.of(Map.of("name", "甲")),
                        "secondRows", List.of(Map.of("name", "乙"))
                ),
                Map.of(),
                "已完成"
        );
        ColumnDefinition column = new ColumnDefinition(
                "name", "名称", ValueType.TEXT, "", Tone.DEFAULT
        );

        assertThatThrownBy(() -> composer.compose(command(
                List.of(dataset),
                List.of(),
                List.of(
                        new TableDefinition(
                                "same_table", "表一", "PROJECT", "firstRows", List.of(column)
                        ),
                        new TableDefinition(
                                "same_table", "表二", "PROJECT", "secondRows", List.of(column)
                        )
                ),
                List.of(), null, false
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Block id重复");

        ArtifactBlock conflictingArtifact = new ArtifactBlock(
                "dataset_status", "报告", 100, BlockStatus.READY, BlockSource.SYSTEM,
                "task-1", "XLSX", "report.xlsx", "COMPLETED", null, true, "已完成"
        );
        assertThatThrownBy(() -> composer.compose(command(
                List.of(dataset), List.of(), List.of(), List.of(),
                conflictingArtifact, false
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Block id重复");
    }

    @Test
    void rejectsContradictoryDatasetAndArtifactCompleteness() {
        for (DatasetExecutionStatus status : List.of(
                DatasetExecutionStatus.FAILED,
                DatasetExecutionStatus.TIMEOUT,
                DatasetExecutionStatus.PENDING
        )) {
            assertThatThrownBy(() -> new DatasetAnswerInput(
                    "PROJECT", "项目", status, true,
                    Map.of(), Map.of(), "状态异常"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dataComplete");
        }
        assertThatThrownBy(() -> new DatasetAnswerInput(
                "PROJECT", "项目", null, true,
                Map.of(), Map.of(), "状态异常"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataComplete");

        for (String taskStatus : List.of("FAILED", "EXPIRED", "PARTIAL_SUCCESS")) {
            assertThatThrownBy(() -> new ArtifactBlock(
                    "artifact", "报告", 1, BlockStatus.READY, BlockSource.SYSTEM,
                    "task-1", "XLSX", "report.xlsx", taskStatus, null, true, "状态异常"
            )).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("dataComplete");
        }
    }

    private ComposeCommand command(
            List<DatasetAnswerInput> datasets,
            List<MetricDefinition> metrics,
            List<TableDefinition> tables,
            List<ProjectIssueResult> issues,
            ArtifactBlock artifact,
            boolean narrativeRequired) {
        return new ComposeCommand(
                "response-1", "run-1", "conversation-1", context(), true,
                datasets, metrics, tables, issues, artifact, narrativeRequired,
                "请说明项目情况", "user-1", "model-1",
                List.of(), ResponseMeta.empty()
        );
    }

    private ResponseContext context() {
        return new ResponseContext(
                " PROJECT ", " P-1001 ", " 项目一 ", " 2026年度 ",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                LocalDateTime.of(2026, 9, 2, 10, 0)
        );
    }

    private StatusListBlock statusBlock() {
        return new StatusListBlock(
                "dataset_status", "数据状态", 10,
                BlockStatus.READY, BlockSource.BUSINESS,
                List.of(
                        new StatusListBlock.StatusItem(
                                "PROJECT", "项目概览", "SUCCESS", Tone.SUCCESS, "已完成"
                        ),
                        new StatusListBlock.StatusItem(
                                "PERSON", "人员数据", "TIMEOUT", Tone.DANGER, "查询超时"
                        )
                )
        );
    }

    private ArtifactBlock artifact(String taskStatus, BlockStatus blockStatus) {
        return new ArtifactBlock(
                "report_artifact", "报告文件", 100,
                blockStatus, BlockSource.SYSTEM,
                "task-1", "XLSX", "项目报告.xlsx", taskStatus,
                LocalDateTime.of(2026, 9, 3, 10, 0),
                "COMPLETED".equals(taskStatus), "报告任务状态"
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> source) {
        return (Map<String, Object>) source;
    }

    private String extractEnvelope(String prompt) {
        String marker = "安全模型输入JSON：";
        int start = prompt.indexOf(marker) + marker.length();
        int end = prompt.indexOf("截断只限制本段模型分析范围", start);
        return prompt.substring(start, end).trim();
    }
}
