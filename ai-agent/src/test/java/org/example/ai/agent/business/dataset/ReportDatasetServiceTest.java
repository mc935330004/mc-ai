package org.example.ai.agent.business.dataset;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(workflowResolver.resolveByCode("QUERY_EMPLOYEE"))
                .thenReturn(publishedWorkflow(
                        "QUERY_EMPLOYEE",
                        "{\"type\":\"object\"}",
                        graphWithCapability("EMPLOYEE_QUERY")
                ));

        ReportDataset updated = service.saveCurrent(
                validDataset("{\"employeeNo\":\"subjectId\"}"),
                List.of(validField("employee_status", 20)),
                "editor"
        );

        assertThat(updated.getId()).isEqualTo(77L);
        assertThat(updated.getCreatedBy()).isEqualTo("creator");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedBy()).isEqualTo("editor");
        verify(datasetMapper, never()).insert(any(ReportDataset.class));
        verify(datasetMapper).updateById(updated);

        InOrder replacementOrder = inOrder(fieldMapper);
        replacementOrder.verify(fieldMapper).delete(any(Wrapper.class));
        replacementOrder.verify(fieldMapper).insert(any(ReportDatasetField.class));
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
        existing.setCreatedBy("admin");
        existing.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        when(datasetMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        ReportDataset second = service.saveCurrent(
                validDataset("{\"period\":{\"start\":\"periodStart\",\"end\":\"periodEnd\"},\"employeeNo\":\"subjectId\"}"),
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
