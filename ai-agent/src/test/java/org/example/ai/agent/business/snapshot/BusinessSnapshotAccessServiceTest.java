package org.example.ai.agent.business.snapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.CanonicalInputMapper;
import org.example.ai.agent.business.dataset.DatasetAccessWorkflowExecutor;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService.AccessCommand;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionCommand;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionFacade;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessSnapshotAccessServiceTest {

    private static final String CONFIG = "a".repeat(64);
    private static final String POLICY = "b".repeat(64);

    private ReportDatasetMapper datasetMapper;
    private WorkflowRuntimeSnapshotResolver resolver;
    private WorkflowExecutionFacade facade;
    private BusinessSnapshotAccessService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(ReportDatasetMapper.class);
        resolver = mock(WorkflowRuntimeSnapshotResolver.class);
        facade = mock(WorkflowExecutionFacade.class);
        objectMapper = new ObjectMapper();
        DatasetAccessWorkflowExecutor executor = new DatasetAccessWorkflowExecutor(
                resolver, facade,
                new CanonicalInputMapper(new ReportDatasetValidator()), objectMapper
        );
        service = new BusinessSnapshotAccessService(datasetMapper, executor, objectMapper);
    }

    @Test
    void shouldExecuteOnlyCurrentAccessWorkflowAndReturnCurrentChecksums() {
        when(datasetMapper.selectOne(any())).thenReturn(dataset());
        when(resolver.resolveByCode("attendance.access")).thenReturn(
                WorkflowFixtures.published(
                        "attendance.access", 21L, accessSchema(objectMapper)
                )
        );
        when(facade.execute(any())).thenReturn(outcome(true));

        var grant = service.reauthorize(command());

        assertThat(grant).isPresent();
        assertThat(grant.orElseThrow().configChecksum()).isEqualTo(CONFIG);
        ArgumentCaptor<WorkflowExecutionCommand> command =
                ArgumentCaptor.forClass(WorkflowExecutionCommand.class);
        verify(facade).execute(command.capture());
        assertThat(command.getValue().getWorkflowCode()).isEqualTo("attendance.access");
        assertThat(command.getValue().getAuthorization()).isEqualTo("Bearer current");
        assertThat(command.getValue().getInput()).containsEntry("employee_code", "E100");
        verify(resolver, never()).resolveByCode("attendance.query");
    }

    @Test
    void shouldReturnSameGenericUnavailableResultForDeniedMissingAndFailure() {
        assertThat(service.reauthorize(command())).isEmpty();

        when(datasetMapper.selectOne(any())).thenReturn(dataset());
        when(resolver.resolveByCode(any())).thenReturn(
                WorkflowFixtures.published(
                        "attendance.access", 21L, accessSchema(objectMapper)
                )
        );
        when(facade.execute(any())).thenReturn(outcome(false));
        assertThat(service.reauthorize(command())).isEmpty();

        when(facade.execute(any())).thenThrow(new IllegalStateException("downstream"));
        assertThat(service.reauthorize(command())).isEmpty();
    }

    @Test
    void accessCommandMustNotPrintCredentialsOrRoleValues() {
        assertThat(command().toString())
                .doesNotContain("Bearer current", "employee", "2026")
                .contains("authorizationPresent=true");
    }

    @Test
    void accessRequestMustNotPrintStableIdentityOrSubjectValues() {
        DatasetAccessWorkflowExecutor.AccessRequest request =
                new DatasetAccessWorkflowExecutor.AccessRequest(
                        "agent-run-secret", "user-secret", "Bearer current",
                        Map.of("roles", List.of("employee")),
                        BusinessSubjectType.PERSON, "E100",
                        Map.of("subjectId", "caller-subject")
                );

        assertThat(request.toString())
                .doesNotContain(
                        "agent-run-secret", "user-secret", "E100", "caller-subject",
                        "Bearer current", "employee"
                )
                .contains(
                        "agentRunIdPresent=true", "userIdPresent=true",
                        "subjectType=PERSON", "subjectIdPresent=true",
                        "authorizationPresent=true"
                );
    }

    @Test
    void shouldFailClosedWhenAccessMappingDoesNotConsumeTrustedSubject() {
        ReportDataset invalid = dataset();
        invalid.setInputMappingJson("{\"access\":{\"year\":\"year\"},\"query\":{}}");
        when(datasetMapper.selectOne(any())).thenReturn(invalid);

        assertThat(service.reauthorize(command())).isEmpty();
        verify(facade, never()).execute(any());
    }

    private AccessCommand command() {
        return new AccessCommand(
                "agent-1", "user-1", "session-1", "Bearer current",
                Map.of("roles", List.of("employee")), "ATTENDANCE",
                BusinessSubjectType.PERSON, "E100",
                Map.of("year", 2026, "subjectId", "E999")
        );
    }

    private ReportDataset dataset() {
        ReportDataset dataset = new ReportDataset();
        dataset.setId(9L);
        dataset.setDatasetCode("ATTENDANCE");
        dataset.setAccessWorkflowCode("attendance.access");
        dataset.setQueryWorkflowCode("attendance.query");
        dataset.setSubjectTypesJson("[\"PERSON\"]");
        dataset.setInputMappingJson("{\"access\":{\"subjectId\":\"employee_code\"},\"query\":{}}");
        dataset.setConfigChecksum(CONFIG);
        dataset.setFieldPolicyChecksum(POLICY);
        dataset.setEnabled(true);
        return dataset;
    }

    private WorkflowExecutionOutcome outcome(boolean allowed) {
        return new WorkflowExecutionOutcome(
                true, false, "run-access", "attendance.access", "access", 21L, 1,
                Map.of("allowed", allowed), null, null, List.of(), 1L
        );
    }

    private static com.fasterxml.jackson.databind.JsonNode accessSchema(ObjectMapper mapper) {
        return mapper.createObjectNode()
                .put("type", "object")
                .set("properties", mapper.createObjectNode()
                        .set("employee_code", mapper.createObjectNode().put("type", "string")));
    }

    /**
     * 测试仅需稳定构造已发布工作流，避免把权限测试和工作流持久化细节耦合。
     */
    private static final class WorkflowFixtures {
        private static PublishedWorkflow published(
                String workflowCode, Long versionId,
                com.fasterxml.jackson.databind.JsonNode inputSchema) {
            org.example.ai.agent.workflow.entity.WorkflowDefinition definition =
                    new org.example.ai.agent.workflow.entity.WorkflowDefinition();
            definition.setWorkflowCode(workflowCode);
            org.example.ai.agent.workflow.entity.WorkflowVersion version =
                    new org.example.ai.agent.workflow.entity.WorkflowVersion();
            version.setId(versionId);
            return new PublishedWorkflow(definition, version, null, inputSchema);
        }
    }
}
