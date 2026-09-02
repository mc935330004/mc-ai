package org.example.ai.agent.business.dataset.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.answer.extractor.DictionaryFactExtractor;
import org.example.ai.agent.answer.formatter.FactValueFormatter;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.CanonicalInputMapper;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.FieldMetadataService;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.graph.compiler.GraphCapabilityCatalog;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.workflow.answer.WorkflowCapabilityCodeCollector;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionCommand;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionFacade;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ReportDatasetExecutionServiceTest {

    private static final String ACCESS = "CHECK_PROJECT_ACCESS";
    private static final String QUERY = "QUERY_PROJECT";
    private static final String SECRET = "Bearer task6-secret";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReportDatasetMapper datasetMapper;
    private ReportDatasetFieldMapper datasetFieldMapper;
    private WorkflowRuntimeSnapshotResolver snapshotResolver;
    private WorkflowExecutionFacade executionFacade;
    private FieldDictionaryMapper fieldDictionaryMapper;
    private GraphCapabilityCatalog capabilityCatalog;
    private DatasetExecutionProofService proofService;
    private ReportDatasetExecutionService service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(ReportDatasetMapper.class);
        datasetFieldMapper = mock(ReportDatasetFieldMapper.class);
        snapshotResolver = mock(WorkflowRuntimeSnapshotResolver.class);
        executionFacade = mock(WorkflowExecutionFacade.class);
        fieldDictionaryMapper = mock(FieldDictionaryMapper.class);
        capabilityCatalog = mock(GraphCapabilityCatalog.class);
        proofService = new DatasetExecutionProofService();
        when(capabilityCatalog.sideEffect(any())).thenReturn("READ");
        FieldMetadataService metadataService = new FieldMetadataService(fieldDictionaryMapper);
        DictionaryFactExtractor extractor = new DictionaryFactExtractor(
                objectMapper,
                new FactValueFormatter(objectMapper)
        );
        ReportDatasetValidator validator = new ReportDatasetValidator();
        service = new ReportDatasetExecutionServiceImpl(
                datasetMapper,
                datasetFieldMapper,
                snapshotResolver,
                executionFacade,
                new CanonicalInputMapper(validator),
                new BusinessFactSanitizer(validator),
                fieldDictionaryMapper,
                metadataService,
                extractor,
                new WorkflowCapabilityCodeCollector(),
                capabilityCatalog,
                proofService,
                objectMapper
        );
    }

    @Test
    void requestRecursivelySnapshotsAndFreezesSecurityAndCanonicalValues() {
        List<Object> scopes = new ArrayList<>(List.of("read"));
        Map<String, Object> secureNested = new LinkedHashMap<>();
        secureNested.put("scopes", scopes);
        Map<String, Object> secureContext = new LinkedHashMap<>();
        secureContext.put("claims", secureNested);

        Object[] filters = new Object[]{"ACTIVE"};
        Map<String, Object> canonicalNested = new LinkedHashMap<>();
        canonicalNested.put("filters", filters);
        Map<String, Object> canonicalInput = new LinkedHashMap<>();
        canonicalInput.put("query", canonicalNested);

        DatasetExecutionRequest request = new DatasetExecutionRequest(
                "agent-run-1", "user-1", "session-1", SECRET,
                secureContext, "PROJECT_BASE", BusinessSubjectType.PROJECT,
                "P100", canonicalInput
        );
        scopes.add("late");
        secureNested.put("late", "must-not-escape");
        filters[0] = "DELETED";
        canonicalNested.put("late", "must-not-escape");

        Map<String, Object> frozenClaims =
                (Map<String, Object>) request.secureContext().get("claims");
        Map<String, Object> frozenQuery =
                (Map<String, Object>) request.canonicalInput().get("query");
        assertThat(frozenClaims).containsOnlyKeys("scopes");
        assertThat((List<Object>) frozenClaims.get("scopes")).containsExactly("read");
        assertThat(frozenQuery).containsOnlyKeys("filters");
        assertThat((List<Object>) frozenQuery.get("filters")).containsExactly("ACTIVE");
        assertThatThrownBy(() -> frozenClaims.put("bad", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) frozenClaims.get("scopes")).add("bad"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requestRejectsCyclicAndUnsupportedNestedValuesWithControlledError() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);

        assertThatThrownBy(() -> new DatasetExecutionRequest(
                "agent-run-1", "user-1", "session-1", SECRET,
                cyclic, "PROJECT_BASE", BusinessSubjectType.PROJECT,
                "P100", Map.of()
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("循环");

        assertThatThrownBy(() -> new DatasetExecutionRequest(
                "agent-run-1", "user-1", "session-1", SECRET,
                Map.of(), "PROJECT_BASE", BusinessSubjectType.PROJECT,
                "P100", Map.of("bad", new StringBuilder("mutable"))
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("StringBuilder")
                .hasMessageContaining("不支持");
    }

    @Test
    void requestToStringDoesNotExposeSecurityOrCanonicalKeysAndValues() {
        DatasetExecutionRequest request = new DatasetExecutionRequest(
                "agent-run-1", "user-1", "session-1", SECRET,
                Map.of("authorizationSecretKey", "authorizationSecretValue"),
                "PROJECT_BASE", BusinessSubjectType.PROJECT, "P100",
                Map.of("canonicalSecretKey", "canonicalSecretValue")
        );

        assertThat(request.toString()).doesNotContain(
                SECRET,
                "authorizationSecretKey",
                "authorizationSecretValue",
                "canonicalSecretKey",
                "canonicalSecretValue"
        );
    }

    @Test
    void deniesWithoutResolvingOrExecutingQueryAndDoesNotRevealExistence() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        when(executionFacade.execute(any())).thenReturn(outcome(
                true, false, "access-run", ACCESS,
                Map.of("allowed", false, "exists", true), null, null
        ));

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.DENIED);
        assertThat(result.dataComplete()).isFalse();
        assertThat(result.safeFacts()).isEmpty();
        assertThat(result.workflowRunId()).isNull();
        assertThat(result.integrityProof()).hasSize(64);
        assertThat(result.toString()).doesNotContain("exists", SECRET);
        verify(executionFacade, times(1)).execute(any());
        verify(snapshotResolver, never()).resolveByCode(QUERY);
    }

    @Test
    void executesDifferentAccessAndQuerySchemasOnceAndForwardsSecretsOnlyInMemory() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        arrangeSingleField("project_name", "$.data.name", true, true, true, true);
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, Map.of("allowed", true), null, null),
                outcome(true, false, "query-run", QUERY,
                        Map.of("data", Map.of("name", "Secret Project")), null, null)
        );

        DatasetExecutionRequest request = request();
        DatasetExecutionResult result = service.execute(request);

        ArgumentCaptor<WorkflowExecutionCommand> commands =
                ArgumentCaptor.forClass(WorkflowExecutionCommand.class);
        verify(executionFacade, times(2)).execute(commands.capture());
        WorkflowExecutionCommand accessCommand = commands.getAllValues().get(0);
        WorkflowExecutionCommand queryCommand = commands.getAllValues().get(1);
        assertThat(accessCommand.getInput()).containsExactly(Map.entry("employee_code", "E1001"));
        assertThat(queryCommand.getInput()).containsExactly(Map.entry("project_no", "P100"));
        assertThat(accessCommand.getAuthorization()).isEqualTo(SECRET);
        assertThat(queryCommand.getAuthorization()).isEqualTo(SECRET);
        assertThat(accessCommand.getSecureContext()).containsEntry("tenantToken", "tenant-secret");
        assertThat(queryCommand.getSecureContext()).containsEntry("tenantToken", "tenant-secret");
        assertThat(accessCommand.getExpectedVersionId()).isEqualTo(11L);
        assertThat(queryCommand.getExpectedVersionId()).isEqualTo(22L);
        assertThat(accessCommand.getAgentRunId()).isEqualTo("agent-run-1");
        assertThat(queryCommand.getAgentRunId()).isEqualTo("agent-run-1");
        assertThat(accessCommand.getRunId()).isNotEqualTo(queryCommand.getRunId());
        assertThat(request.toString()).doesNotContain(SECRET, "tenant-secret");
        assertThat(accessCommand.toString()).doesNotContain(SECRET, "tenant-secret");
        assertThat(result.toString()).doesNotContain("Secret Project", SECRET, "tenant-secret");
        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.SUCCESS);
        assertThat(result.workflowRunId()).isEqualTo("query-run");
        assertThat(result.source().userId()).isEqualTo("user-1");
        assertThat(result.source().sessionId()).isEqualTo("session-1");
        assertThat(result.source().subjectType()).isEqualTo(BusinessSubjectType.PROJECT);
        assertThat(result.source().subjectId()).isEqualTo("P100");
        assertThat(result.source().datasetCode()).isEqualTo("PROJECT_BASE");
        assertThat(result.source().canonicalInputHash()).hasSize(64);
        assertThat(result.source().queryWorkflowCode()).isEqualTo(QUERY);
        assertThat(result.source().queryWorkflowVersionId()).isEqualTo(22L);
        assertThat(result.source().datasetConfigChecksum()).isEqualTo("a".repeat(64));
        assertThat(result.source().fieldPolicyChecksum()).isEqualTo("b".repeat(64));
        assertThat(result.integrityProof()).hasSize(64);
        assertThat(result.toString()).contains("proofPresent=true")
                .doesNotContain(result.integrityProof());
    }

    @Test
    void failsClosedWhenQueryMappingIsMissingAndDoesNotExecuteQuery() throws Exception {
        arrangeDataset("""
                {"access":{"employeeNo":"employee_code"},"query":{}}
                """);
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        when(executionFacade.execute(any())).thenReturn(outcome(
                true, false, "access-run", ACCESS, true, null, null
        ));

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(result.integrityProof()).hasSize(64);
        assertThat(result.safeMessage()).doesNotContain("project_no", SECRET);
        verify(executionFacade, times(1)).execute(any());
    }

    @Test
    void failsClosedWhenAccessThrowsOrReturnsIllegalShape() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        when(executionFacade.execute(any())).thenThrow(new IllegalStateException("raw auth failure " + SECRET));

        DatasetExecutionResult exceptionResult = service.execute(request());

        assertThat(exceptionResult.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(exceptionResult.safeMessage()).doesNotContain("raw auth failure", SECRET);
        verify(snapshotResolver, never()).resolveByCode(QUERY);

        doReturn(outcome(
                true, false, "access-run", ACCESS, Map.of("allowed", "yes"), null, null
        )).when(executionFacade).execute(any());
        DatasetExecutionResult illegalResult = service.execute(request());

        assertThat(illegalResult.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        verify(executionFacade, times(2)).execute(any());
        verify(snapshotResolver, never()).resolveByCode(QUERY);
    }

    @Test
    void distinguishesQueryTimeoutFailureAndEmptyResult() throws Exception {
        DatasetExecutionResult timeout = executeQueryOutcome(outcome(
                false, false, "timeout-run", QUERY, null, "TIMEOUT", "raw timeout details"
        ));
        assertThat(timeout.status()).isEqualTo(DatasetExecutionStatus.TIMEOUT);
        assertThat(timeout.integrityProof()).hasSize(64);
        assertThat(timeout.safeMessage()).doesNotContain("raw timeout details");
        assertThat(timeout.source().queryWorkflowVersionId()).isEqualTo(22L);

        resetExecutions();
        DatasetExecutionResult failed = executeQueryOutcome(outcome(
                false, false, "failed-run", QUERY, null, "DOWNSTREAM_FAILURE", "raw failure"
        ));
        assertThat(failed.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(failed.integrityProof()).hasSize(64);
        assertThat(failed.safeMessage()).doesNotContain("raw failure");

        resetExecutions();
        arrangeSingleField("project_name", "$.data.name", true, true, true, true);
        DatasetExecutionResult empty = executeQueryOutcome(outcome(
                true, false, "empty-run", QUERY, Map.of("data", Map.of()), null, null
        ));
        assertThat(empty.status()).isEqualTo(DatasetExecutionStatus.EMPTY);
        assertThat(empty.integrityProof()).hasSize(64);
        assertThat(empty.dataComplete()).isFalse();
    }

    @Test
    void keepsPartialSuccessStatusButMarksDataIncomplete() throws Exception {
        arrangeSingleField("project_name", "$.data.name", true, true, true, true);

        DatasetExecutionResult result = executeQueryOutcome(outcome(
                true, true, "partial-run", QUERY,
                Map.of("data", Map.of("name", "Project A")), null, null
        ));

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.SUCCESS);
        assertThat(result.dataComplete()).isFalse();
    }

    @Test
    void mapsPublishedDictionaryValuesToFactCodeAndSanitizesFourIndependentChannels() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        ReportDatasetField name = datasetField(
                101L, "project_name", true, true, false, false
        );
        ReportDatasetField account = datasetField(
                102L, "bank_account", true, false, true, true
        );
        when(datasetFieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(name, account));
        when(fieldDictionaryMapper.selectBatchIds(any())).thenReturn(List.of(
                dictionary(101L, "source_name", "$.data.records[].name", 1, 1),
                dictionary(102L, "source_account", "$.data.records[].account", 1, 1)
        ));
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                outcome(true, false, "query-run", QUERY, Map.of(
                        "data", Map.of(
                                "records", List.of(
                                        Map.of("name", "A", "account", "62220001", "unknownRaw", SECRET),
                                        Map.of("name", "B", "account", "62220002")
                                ),
                                "unknownTop", SECRET
                        )
                ), null, null)
        );

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.safeFacts()).containsOnlyKeys("calculation", "display", "export", "model");
        Map<String, Object> calculation = channel(result, "calculation");
        Map<String, Object> display = channel(result, "display");
        Map<String, Object> export = channel(result, "export");
        Map<String, Object> model = channel(result, "model");
        assertThat(calculation.get("project_name")).isEqualTo(List.of("A", "B"));
        assertThat(calculation.get("bank_account")).isEqualTo(List.of("62220001", "62220002"));
        assertThat(display).containsOnlyKeys("project_name");
        assertThat(export).containsOnlyKeys("bank_account");
        assertThat(model).containsOnlyKeys("bank_account");
        assertThat(result.safeFacts().toString()).doesNotContain("unknownRaw", "unknownTop", SECRET);
    }

    @Test
    void refusesDatasetPolicyThatBreaksPublishedDictionaryVisibility() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        ReportDatasetField field = datasetField(101L, "private_name", true, true, true, true);
        when(datasetFieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(field));
        when(fieldDictionaryMapper.selectBatchIds(any())).thenReturn(List.of(
                dictionary(101L, "private_name", "$.data.name", 0, 0)
        ));
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                outcome(true, false, "query-run", QUERY,
                        Map.of("data", Map.of("name", SECRET)), null, null)
        );

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(result.safeFacts()).isEmpty();
        assertThat(result.workflowRunId()).isEqualTo("query-run");
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @Test
    void refusesExportPolicyThatBreaksPublishedDictionaryUserVisibility() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        ReportDatasetField exportOnly = datasetField(
                101L, "private_export", false, false, true, false
        );
        when(datasetFieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(exportOnly));
        when(fieldDictionaryMapper.selectBatchIds(any())).thenReturn(List.of(
                dictionary(101L, "private_export", "$.data.value", 0, 1)
        ));
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                outcome(true, false, "query-run", QUERY,
                        Map.of("data", Map.of("value", SECRET)), null, null)
        );

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(result.safeFacts()).isEmpty();
        assertThat(result.toString()).doesNotContain(SECRET);
    }

    @Test
    void refusesDictionaryFromCapabilityOutsideCurrentQueryWorkflow() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(
                QUERY,
                22L,
                schema("project_no"),
                graphWithCapability("PROJECT_READ")
        );
        arrangeSingleField("project_name", "$.data.name", true, true, true, true);
        FieldDictionary unrelated = dictionary(
                101L, "source_project_name", "$.data.name", 1, 1
        );
        unrelated.setCapabilityCode("OTHER_READ");
        when(fieldDictionaryMapper.selectBatchIds(any())).thenReturn(List.of(unrelated));
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                outcome(true, false, "query-run", QUERY,
                        Map.of("data", Map.of("name", SECRET)), null, null)
        );

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(result.safeFacts()).isEmpty();
    }

    @Test
    void refusesDictionaryWhenCurrentWorkflowCapabilityIsNotReadOnly() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(
                QUERY,
                22L,
                schema("project_no"),
                graphWithCapability("PROJECT_WRITE")
        );
        ReportDatasetField field = datasetField(
                101L, "project_name", true, true, true, true
        );
        FieldDictionary dictionary = dictionary(
                101L, "source_project_name", "$.data.name", 1, 1
        );
        dictionary.setCapabilityCode("PROJECT_WRITE");
        when(datasetFieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(field));
        when(fieldDictionaryMapper.selectBatchIds(any())).thenReturn(List.of(dictionary));
        when(capabilityCatalog.sideEffect("PROJECT_WRITE")).thenReturn("WRITE");
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                outcome(true, false, "query-run", QUERY,
                        Map.of("data", Map.of("name", SECRET)), null, null)
        );

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(result.safeFacts()).isEmpty();
        verify(executionFacade, times(1)).execute(any());
    }

    @Test
    void failsClosedBeforeDictionaryLookupWhenDatasetFieldIdIsMissing() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        ReportDatasetField field = datasetField(
                101L, "project_name", true, true, true, true
        );
        field.setFieldId(null);
        when(datasetFieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(field));
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                outcome(true, false, "query-run", QUERY,
                        Map.of("data", Map.of("name", SECRET)), null, null)
        );

        DatasetExecutionResult result = service.execute(request());

        assertThat(result.status()).isEqualTo(DatasetExecutionStatus.FAILED);
        assertThat(result.safeFacts()).isEmpty();
        verify(fieldDictionaryMapper, never()).selectBatchIds(any());
    }

    @Test
    void resultUsesSharedSafeValuePolicyForUnsupportedFacts() {
        assertThatThrownBy(() -> new DatasetExecutionResult(
                null,
                DatasetExecutionStatus.SUCCESS,
                true,
                Map.of("calculation", Map.of("bad", new StringBuilder("mutable"))),
                "query-run",
                null,
                null,
                "数据查询完成",
                null
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("StringBuilder")
                .hasMessageContaining("不支持");
    }

    @Test
    void freezesNestedFactsAndDoesNotRetainMutableRawResult() throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        arrangeSingleField("tags", "$.data.tags", true, true, false, false);
        List<Object> mutableTags = new ArrayList<>(List.of("first"));
        Map<String, Object> mutableData = new LinkedHashMap<>();
        mutableData.put("tags", mutableTags);
        Map<String, Object> mutableRaw = new LinkedHashMap<>();
        mutableRaw.put("data", mutableData);
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                outcome(true, false, "query-run", QUERY, mutableRaw, null, null)
        );

        DatasetExecutionResult result = service.execute(request());
        mutableTags.add("late");
        mutableData.put("late", SECRET);

        Map<String, Object> calculation = channel(result, "calculation");
        assertThat(calculation.get("tags")).isEqualTo(List.of("first"));
        assertThatThrownBy(() -> result.safeFacts().put("raw", mutableRaw))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> calculation.put("raw", mutableRaw))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) calculation.get("tags")).add("bad"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private DatasetExecutionResult executeQueryOutcome(WorkflowExecutionOutcome queryOutcome)
            throws Exception {
        arrangeDataset(defaultMapping());
        arrangeWorkflow(ACCESS, 11L, schema("employee_code"));
        arrangeWorkflow(QUERY, 22L, schema("project_no"));
        when(executionFacade.execute(any())).thenReturn(
                outcome(true, false, "access-run", ACCESS, true, null, null),
                queryOutcome
        );
        return service.execute(request());
    }

    private void resetExecutions() {
        org.mockito.Mockito.reset(
                datasetMapper,
                datasetFieldMapper,
                snapshotResolver,
                executionFacade,
                fieldDictionaryMapper
        );
    }

    private void arrangeDataset(String mappingJson) {
        ReportDataset dataset = new ReportDataset();
        dataset.setId(1L);
        dataset.setDatasetCode("PROJECT_BASE");
        dataset.setSubjectTypesJson("[\"PROJECT\"]");
        dataset.setAccessWorkflowCode(ACCESS);
        dataset.setQueryWorkflowCode(QUERY);
        dataset.setInputMappingJson(mappingJson);
        dataset.setConfigChecksum("a".repeat(64));
        dataset.setFieldPolicyChecksum("b".repeat(64));
        dataset.setEnabled(true);
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(dataset);
    }

    private void arrangeWorkflow(String code, long versionId, JsonNode inputSchema) {
        CompiledGraphSpec graph = QUERY.equals(code)
                ? graphWithForEachBody(graphWithCapability("PROJECT_READ"))
                : graphWithCapability("ACCESS_READ");
        arrangeWorkflow(code, versionId, inputSchema, graph);
    }

    private void arrangeWorkflow(
            String code,
            long versionId,
            JsonNode inputSchema,
            CompiledGraphSpec graph) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowCode(code);
        WorkflowVersion version = new WorkflowVersion();
        version.setId(versionId);
        when(snapshotResolver.resolveByCode(code)).thenReturn(
                new PublishedWorkflow(definition, version, graph, inputSchema)
        );
    }

    private void arrangeSingleField(
            String factCode,
            String path,
            boolean calculable,
            boolean displayable,
            boolean exportable,
            boolean modelVisible) {
        ReportDatasetField field = datasetField(
                101L, factCode, calculable, displayable, exportable, modelVisible
        );
        when(datasetFieldMapper.selectList(any(Wrapper.class))).thenReturn(List.of(field));
        when(fieldDictionaryMapper.selectBatchIds(any())).thenReturn(List.of(
                dictionary(101L, "source_" + factCode, path, 1, 1)
        ));
    }

    private ReportDatasetField datasetField(
            long fieldId,
            String factCode,
            boolean calculable,
            boolean displayable,
            boolean exportable,
            boolean modelVisible) {
        ReportDatasetField field = new ReportDatasetField();
        field.setId(fieldId + 1000);
        field.setDatasetId(1L);
        field.setFieldId(fieldId);
        field.setFactCode(factCode);
        field.setFactType("string");
        field.setCalculable(calculable);
        field.setDisplayable(displayable);
        field.setExportable(exportable);
        field.setModelVisible(modelVisible);
        field.setMaskStrategy("NONE");
        field.setGrain("ITEM");
        field.setDisplayOrder((int) fieldId);
        return field;
    }

    private FieldDictionary dictionary(
            long id,
            String fieldCode,
            String path,
            int userVisible,
            int modelVisible) {
        FieldDictionary dictionary = new FieldDictionary();
        dictionary.setId(id);
        dictionary.setCapabilityCode("PROJECT_READ");
        dictionary.setFieldPath(path);
        dictionary.setFieldName(fieldCode);
        dictionary.setFieldCode(fieldCode);
        dictionary.setFieldCnName(fieldCode);
        dictionary.setFieldType("string");
        dictionary.setDisplayFormat("text");
        dictionary.setRequiredOutput(0);
        dictionary.setUserVisible(userVisible);
        dictionary.setModelVisible(modelVisible);
        dictionary.setDisplayOrder((int) id);
        dictionary.setPublishStatus("PUBLISHED");
        return dictionary;
    }

    private CompiledGraphSpec graphWithCapability(String capabilityCode) {
        CompiledGraphNode node = new CompiledGraphNode(
                "capability",
                GraphNodeType.CAPABILITY,
                "能力",
                null,
                "result",
                new CapabilityNodeConfig(capabilityCode, Map.of(), null)
        );
        return graph(Map.of(node.id(), node), List.of(node.id()));
    }

    private CompiledGraphSpec graphWithForEachBody(CompiledGraphSpec body) {
        CompiledGraphNode node = new CompiledGraphNode(
                "foreach",
                GraphNodeType.FOREACH,
                "循环",
                null,
                "items",
                new CompiledForEachNodeConfig(
                        "$.items",
                        10,
                        2,
                        false,
                        false,
                        null,
                        body
                )
        );
        return graph(Map.of(node.id(), node), List.of(node.id()));
    }

    private CompiledGraphSpec graph(
            Map<String, CompiledGraphNode> nodes,
            List<String> topologicalOrder) {
        Map<String, List<org.example.ai.agent.graph.model.GraphEdgeSpec>> outgoing =
                new LinkedHashMap<>();
        Map<String, List<org.example.ai.agent.graph.model.GraphEdgeSpec>> incoming =
                new LinkedHashMap<>();
        nodes.keySet().forEach(nodeId -> {
            outgoing.put(nodeId, List.of());
            incoming.put(nodeId, List.of());
        });
        return new CompiledGraphSpec(
                "1.0",
                "test_graph",
                "测试图",
                topologicalOrder.get(0),
                topologicalOrder.get(topologicalOrder.size() - 1),
                nodes,
                List.of(),
                outgoing,
                incoming,
                topologicalOrder
        );
    }

    private DatasetExecutionRequest request() {
        Map<String, Object> secureContext = new LinkedHashMap<>();
        secureContext.put("tenantToken", "tenant-secret");
        return new DatasetExecutionRequest(
                "agent-run-1",
                "user-1",
                "session-1",
                SECRET,
                secureContext,
                "PROJECT_BASE",
                BusinessSubjectType.PROJECT,
                "P100",
                Map.of("employeeNo", "E1001", "projectCode", "P100")
        );
    }

    private WorkflowExecutionOutcome outcome(
            boolean success,
            boolean partialSuccess,
            String runId,
            String workflowCode,
            Object result,
            String errorCode,
            String errorMessage) {
        return new WorkflowExecutionOutcome(
                success,
                partialSuccess,
                runId,
                workflowCode,
                workflowCode,
                1L,
                1,
                result,
                errorCode,
                errorMessage,
                List.of(),
                10L
        );
    }

    private JsonNode schema(String requiredName) throws Exception {
        return objectMapper.readTree("""
                {"type":"object","properties":{"%s":{"type":"string"}},"required":["%s"]}
                """.formatted(requiredName, requiredName));
    }

    private String defaultMapping() {
        return """
                {
                  "access":{"employeeNo":"employee_code"},
                  "query":{"projectCode":"project_no"}
                }
                """;
    }

    private Map<String, Object> channel(DatasetExecutionResult result, String name) {
        return (Map<String, Object>) result.safeFacts().get(name);
    }
}
