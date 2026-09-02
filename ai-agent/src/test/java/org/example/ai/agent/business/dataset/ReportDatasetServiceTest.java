package org.example.ai.agent.business.dataset;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.impl.ReportDatasetServiceImpl;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.graph.compiler.GraphCapabilityCatalog;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ReportDatasetServiceTest {

    private ReportDatasetMapper datasetMapper;
    private ReportDatasetFieldMapper fieldMapper;
    private WorkflowRuntimeSnapshotResolver workflowResolver;
    private GraphCapabilityCatalog capabilityCatalog;
    private ReportDatasetServiceImpl service;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(ReportDatasetMapper.class);
        fieldMapper = mock(ReportDatasetFieldMapper.class);
        workflowResolver = mock(WorkflowRuntimeSnapshotResolver.class);
        capabilityCatalog = mock(GraphCapabilityCatalog.class);
        service = new ReportDatasetServiceImpl(
                datasetMapper,
                fieldMapper,
                workflowResolver,
                capabilityCatalog,
                new ObjectMapper()
        );

        when(capabilityCatalog.sideEffect(anyString())).thenReturn("READ");
        when(datasetMapper.insert(any(ReportDataset.class))).thenAnswer(invocation -> {
            ReportDataset inserted = invocation.getArgument(0);
            inserted.setId(101L);
            return 1;
        });
        when(datasetMapper.updateById(any(ReportDataset.class))).thenReturn(1);
        when(fieldMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(fieldMapper.insert(any(ReportDatasetField.class))).thenReturn(1);
    }

    @Test
    void savesCurrentDatasetWhenQueryWorkflowIsPublishedEnabledAndReadOnly() {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\",\"properties\":{\"employeeNo\":{\"type\":\"string\"}}}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));

        ReportDataset saved = service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\"}"),
                List.of(validField("employee_name", 10)),
                "admin"
        );

        assertThat(saved.getId()).isEqualTo(101L);
        assertThat(saved.getConfigChecksum()).hasSize(64);
        assertThat(saved.getFieldPolicyChecksum()).hasSize(64);
        assertThat(saved.getCreatedBy()).isEqualTo("admin");
        assertThat(saved.getUpdatedBy()).isEqualTo("admin");
        verify(datasetMapper).insert(saved);
        verify(fieldMapper).delete(any(Wrapper.class));
        verify(fieldMapper).insert(any(ReportDatasetField.class));
    }

    @Test
    void allowsAccessAndQueryWorkflowsToHaveDifferentInputSchemas() {
        ReportDataset dataset = validDataset("{\"employeeNo\":\"subjectId\"}");
        dataset.setAccessWorkflowCode("CHECK_EMPLOYEE_ACCESS");

        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\",\"required\":[\"employeeNo\"]}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));
        when(workflowResolver.resolveByCode("CHECK_EMPLOYEE_ACCESS"))
                .thenReturn(publishedWorkflow(
                        "CHECK_EMPLOYEE_ACCESS",
                        "{\"type\":\"object\",\"required\":[\"userId\",\"resourceCode\"]}",
                        graphWithCapability("EMPLOYEE_ACCESS_CHECK")
                ));

        ReportDataset saved = service.saveCurrent(
                dataset,
                List.of(validField("employee_name", 10)),
                "admin"
        );

        assertThat(saved.getId()).isEqualTo(101L);
        verify(workflowResolver).resolveByCode("QUERY_EMPLOYEE");
        verify(workflowResolver).resolveByCode("CHECK_EMPLOYEE_ACCESS");
    }

    @Test
    void rejectsWriteCapabilityNestedInsideForEachBody() {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithForEachBody(graphWithCapability("EMPLOYEE_UPDATE"))
                ));
        when(capabilityCatalog.sideEffect("EMPLOYEE_UPDATE")).thenReturn("WRITE");

        assertThatThrownBy(() -> service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\"}"),
                List.of(validField("employee_name", 10)),
                "admin"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("READ")
                .hasMessageContaining("EMPLOYEE_UPDATE");

        verify(datasetMapper, never()).insert(any(ReportDataset.class));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "UNKNOWN", "DANGEROUS"})
    void rejectsMainGraphCapabilityUnlessSideEffectIsExplicitlyRead(String sideEffect) {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));
        when(capabilityCatalog.sideEffect("EMPLOYEE_QUERY")).thenReturn(sideEffect);

        assertThatThrownBy(() -> service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\"}"),
                List.of(validField("employee_name", 10)),
                "admin"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只允许")
                .hasMessageContaining("READ")
                .hasMessageContaining("EMPLOYEE_QUERY");

        verify(datasetMapper, never()).insert(any(ReportDataset.class));
    }

    @Test
    void rejectsUnknownSideEffectNestedInsideForEachBody() {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithForEachBody(graphWithCapability("EMPLOYEE_NESTED_QUERY"))
                ));
        when(capabilityCatalog.sideEffect("EMPLOYEE_NESTED_QUERY")).thenReturn("UNKNOWN");

        assertThatThrownBy(() -> service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\"}"),
                List.of(validField("employee_name", 10)),
                "admin"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("READ")
                .hasMessageContaining("EMPLOYEE_NESTED_QUERY");
    }

    @Test
    void rejectsDuplicateFactCodeInOneDataset() {
        assertThatThrownBy(() -> service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\"}"),
                List.of(
                        validField("employee_name", 10),
                        validField(" employee_name ", 20)
                ),
                "admin"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("factCode")
                .hasMessageContaining("重复");

        verify(workflowResolver, never()).resolveByCode(anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1441})
    void rejectsTtlOutsideOneTo1440Minutes(int invalidTtl) {
        ReportDataset dataset = validDataset("{\"employeeNo\":\"subjectId\"}");
        dataset.setTtlMinutes(invalidTtl);

        assertThatThrownBy(() -> service.saveCurrent(
                dataset,
                List.of(validField("employee_name", 10)),
                "admin"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TTL")
                .hasMessageContaining("1..1440");

        verify(workflowResolver, never()).resolveByCode(anyString());
    }

    @Test
    void rejectsMissingOperator() {
        assertThatThrownBy(() -> service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\"}"),
                List.of(validField("employee_name", 10)),
                " "
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("操作人");
    }

    @Test
    void updatesExistingCurrentRowAndReplacesItsFields() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 10, 0);
        ReportDataset existing = validDataset("{\"employeeNo\":\"subjectId\"}");
        existing.setId(77L);
        existing.setCreatedBy("creator");
        existing.setCreatedAt(createdAt);
        existing.setVersion(6);
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));

        ReportDataset request = validDataset("{\"employeeNo\":\"subjectId\"}");
        request.setVersion(5);
        ReportDataset updated = service.saveCurrent(
                request,
                List.of(validField("employee_status", 20)),
                "editor"
        );

        assertThat(updated.getId()).isEqualTo(77L);
        assertThat(updated.getCreatedBy()).isEqualTo("creator");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedBy()).isEqualTo("editor");
        assertThat(updated.getVersion()).isEqualTo(5);
        verify(datasetMapper, never()).insert(any(ReportDataset.class));
        verify(datasetMapper).updateById(updated);

        InOrder replacementOrder = inOrder(fieldMapper);
        replacementOrder.verify(fieldMapper).delete(any(Wrapper.class));
        replacementOrder.verify(fieldMapper).insert(any(ReportDatasetField.class));
    }

    @Test
    void declaresVersionColumnAndOptimisticLockField() throws Exception {
        String migration;
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V12__create_business_assistant_config.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .containsPattern("(?i)version\\s+INT\\s+NOT\\s+NULL\\s+DEFAULT\\s+0");
        assertThat(ReportDataset.class.getDeclaredField("version").getAnnotation(Version.class))
                .isNotNull();
    }

    @Test
    void forcesVersionZeroWhenCreatingCurrentDataset() {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));
        ReportDataset request = validDataset("{}");

        ReportDataset saved = service.saveCurrent(
                request,
                List.of(validField("employee_name", 10)),
                "admin"
        );

        assertThat(saved.getVersion()).isZero();
    }

    @Test
    void requiresClientVersionWhenUpdatingCurrentDataset() {
        ReportDataset existing = existingDataset(77L, 4);
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));

        assertThatThrownBy(() -> service.saveCurrent(
                validDataset("{}"),
                List.of(validField("employee_name", 10)),
                "editor"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("不能为空");

        verify(datasetMapper, never()).updateById(any(ReportDataset.class));
    }

    @Test
    void reportsOptimisticConflictWhenUpdateAffectsNoRow() {
        ReportDataset existing = existingDataset(77L, 6);
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(datasetMapper.updateById(any(ReportDataset.class))).thenReturn(0);
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));
        ReportDataset request = validDataset("{}");
        request.setVersion(5);

        BusinessException conflict = catchThrowableOfType(BusinessException.class, () -> service.saveCurrent(
                request,
                List.of(validField("employee_name", 10)),
                "editor"
        ));

        assertThat(conflict.getCode()).isEqualTo(409);
        assertThat(conflict).hasMessageContaining("配置已被其他人修改");
    }

    @Test
    void convertsConcurrentDatasetCodeInsertToBusinessConflict() {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));
        when(datasetMapper.insert(any(ReportDataset.class)))
                .thenThrow(new DuplicateKeyException("Duplicate entry EMPLOYEE_PROFILE"));

        BusinessException conflict = catchThrowableOfType(BusinessException.class, () -> service.saveCurrent(
                validDataset("{}"),
                List.of(validField("employee_name", 10)),
                "admin"
        ));

        assertThat(conflict.getCode()).isEqualTo(409);
        assertThat(conflict)
                .hasMessageContaining("相同datasetCode")
                .hasMessageNotContaining("Duplicate entry");
    }

    @Test
    void normalizesSubjectTypeSetInEnumOrderAndProducesStableChecksum() {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));
        ReportDataset reordered = validDataset("{}");
        reordered.setSubjectTypesJson("[\" DEPARTMENT \",\"PROJECT\",\" PERSON \"]");
        ReportDataset canonical = validDataset("{}");
        canonical.setSubjectTypesJson("[\"PROJECT\",\"PERSON\",\"DEPARTMENT\"]");

        ReportDataset first = service.saveCurrent(
                reordered,
                List.of(validField("employee_name", 10)),
                "admin"
        );
        ReportDataset second = service.saveCurrent(
                canonical,
                List.of(validField("employee_name", 10)),
                "admin"
        );

        assertThat(first.getSubjectTypesJson())
                .isEqualTo("[\"PROJECT\",\"PERSON\",\"DEPARTMENT\"]");
        assertThat(second.getConfigChecksum()).isEqualTo(first.getConfigChecksum());
    }

    @Test
    void rejectsDuplicateSubjectTypeAfterTrimming() {
        ReportDataset dataset = validDataset("{}");
        dataset.setSubjectTypesJson("[\"PERSON\",\" PERSON \"]");

        assertThatThrownBy(() -> service.saveCurrent(
                dataset,
                List.of(validField("employee_name", 10)),
                "admin"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("主体类型")
                .hasMessageContaining("重复");

        verify(workflowResolver, never()).resolveByCode(anyString());
    }

    @Test
    void rejectsUnknownBusinessSubjectType() {
        ReportDataset dataset = validDataset("{}");
        dataset.setSubjectTypesJson("[\"TEAM\"]");

        assertThatThrownBy(() -> service.saveCurrent(
                dataset,
                List.of(validField("employee_name", 10)),
                "admin"
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("主体类型")
                .hasMessageContaining("TEAM");

        verify(workflowResolver, never()).resolveByCode(anyString());
    }

    @Test
    void producesStableChecksumsForJsonKeyAndFieldOrderChanges() {
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ReportDataset first = service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\",\"period\":{\"end\":\"periodEnd\",\"start\":\"periodStart\"}}"),
                List.of(
                        validField("employee_status", 20),
                        validField("employee_name", 10)
                ),
                "admin"
        );
        String firstConfigChecksum = first.getConfigChecksum();
        String firstFieldChecksum = first.getFieldPolicyChecksum();

        ReportDataset existing = validDataset("{}");
        existing.setId(101L);
        existing.setVersion(0);
        existing.setCreatedBy("admin");
        existing.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ReportDataset secondRequest = validDataset("{\"period\":{\"start\":\"periodStart\",\"end\":\"periodEnd\"},\"employeeNo\":\"subjectId\"}");
        secondRequest.setVersion(0);
        ReportDataset second = service.saveCurrent(
                secondRequest,
                List.of(
                        validField("employee_name", 10),
                        validField("employee_status", 20)
                ),
                "admin"
        );

        assertThat(second.getConfigChecksum()).isEqualTo(firstConfigChecksum);
        assertThat(second.getFieldPolicyChecksum()).isEqualTo(firstFieldChecksum);
        verify(datasetMapper, times(1)).insert(any(ReportDataset.class));
        verify(datasetMapper, times(1)).updateById(any(ReportDataset.class));
    }

    private ReportDataset validDataset(String inputMappingJson) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode("EMPLOYEE_PROFILE");
        dataset.setDatasetName("员工档案");
        dataset.setDomainCode("HR");
        dataset.setSubjectTypesJson("[\"PERSON\"]");
        dataset.setQueryWorkflowCode("QUERY_EMPLOYEE");
        dataset.setInputMappingJson(inputMappingJson);
        dataset.setTtlMinutes(60);
        dataset.setAssociationMode("PROJECT_PERSON_PERIOD");
        dataset.setMaxConcurrency(4);
        dataset.setEnabled(true);
        return dataset;
    }

    private ReportDataset existingDataset(long id, int version) {
        ReportDataset existing = validDataset("{}");
        existing.setId(id);
        existing.setCreatedBy("creator");
        existing.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        existing.setVersion(version);
        return existing;
    }

    private ReportDatasetField validField(String factCode, int displayOrder) {
        ReportDatasetField field = new ReportDatasetField();
        field.setFieldId(11L);
        field.setFactCode(factCode);
        field.setFactName("员工姓名");
        field.setFactType("STRING");
        field.setCalculable(false);
        field.setDisplayable(true);
        field.setExportable(true);
        field.setModelVisible(true);
        field.setFilterable(true);
        field.setMaskStrategy("NONE");
        field.setGrain("PERSON");
        field.setDisplayOrder(displayOrder);
        return field;
    }

    private PublishedWorkflow publishedWorkflow(
            String workflowCode,
            String inputSchemaJson,
            CompiledGraphSpec graph) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setId(1L);
        definition.setWorkflowCode(workflowCode);
        definition.setEnabled(1);
        definition.setPublishStatus("PUBLISHED");

        WorkflowVersion version = new WorkflowVersion();
        version.setId(1L);
        version.setWorkflowId(1L);
        version.setWorkflowCode(workflowCode);
        version.setStatus("ACTIVE");

        try {
            return new PublishedWorkflow(
                    definition,
                    version,
                    graph,
                    new ObjectMapper().readTree(inputSchemaJson)
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
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
        Map<String, List<org.example.ai.agent.graph.model.GraphEdgeSpec>> outgoing = new LinkedHashMap<>();
        Map<String, List<org.example.ai.agent.graph.model.GraphEdgeSpec>> incoming = new LinkedHashMap<>();
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
}
