package org.example.ai.agent.business.subject;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowBackedDepartmentMemberDirectoryServiceTest {

    private static final String DATASET_CODE = "DEPARTMENT_MEMBER_DIRECTORY";

    @Mock
    private ReportDatasetExecutionService executionService;
    @Mock
    private DatasetExecutionProofVerifier proofVerifier;
    @Mock
    private ReportDatasetMapper datasetMapper;
    @Mock
    private ReportDatasetFieldMapper datasetFieldMapper;

    private WorkflowBackedDepartmentMemberDirectoryService service;

    @BeforeEach
    void setUp() {
        service = service(DATASET_CODE);
        arrangeDirectoryContract();
    }

    @Test
    void executesConfiguredMemberDirectoryWithCurrentLoginIdentity() {
        DatasetExecutionResult result = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(personCandidate()),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        SubjectDirectoryPage page = service.search(query());

        ArgumentCaptor<DatasetExecutionRequest> request =
                ArgumentCaptor.forClass(DatasetExecutionRequest.class);
        verify(executionService).execute(request.capture());
        assertThat(request.getValue().datasetCode()).isEqualTo(DATASET_CODE);
        assertThat(request.getValue().subjectType()).isEqualTo(BusinessSubjectType.PERSON);
        assertThat(request.getValue().subjectId()).isEqualTo("login-user-1");
        assertThat(request.getValue().authorization()).isEqualTo("Bearer department-secret");
        assertThat(request.getValue().secureContext())
                .containsExactlyEntriesOf(Map.of("tenant", "department-context"));
        assertThat(request.getValue().canonicalInput()).containsExactlyEntriesOf(Map.of(
                "departmentSubjectId", "department-ref-1",
                "pageNumber", 1,
                "pageSize", 20
        ));
        assertThat(page.accessible()).isTrue();
    }

    @Test
    void returnsOnlyPersonCandidatesWithMaskedEmployeeNumbers() {
        DatasetExecutionResult result = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(personCandidate()),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        SubjectDirectoryPage page = service.search(query());

        assertThat(page.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.type()).isEqualTo(BusinessSubjectType.PERSON);
            assertThat(candidate.rawSubjectId()).isEqualTo("person-ref-1");
            assertThat(candidate.maskedEmployeeNo()).isEqualTo("E***01");
            assertThat(candidate.projectCode()).isNull();
        });
    }

    @Test
    void failsClosedWhenDatasetCodeIsMissingOrMalformed() {
        assertThat(service(" ").search(query()).accessible()).isFalse();
        assertThat(service("department-member-directory").search(query()).accessible())
                .isFalse();

        verify(executionService, never()).execute(any());
    }

    @Test
    void failsClosedWhenProofOrSourceDoesNotMatch() {
        DatasetExecutionResult unsigned = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(personCandidate()),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        DatasetExecutionResult wrongSource = result(
                source("another-user", canonicalHash()),
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(personCandidate()),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(unsigned, wrongSource);
        when(proofVerifier.verify(unsigned)).thenReturn(false);
        when(proofVerifier.verify(wrongSource)).thenReturn(true);

        assertThat(service.search(query()).accessible()).isFalse();
        assertThat(service.search(query()).accessible()).isFalse();
    }

    @Test
    void failsClosedWhenNonDisplayChannelContainsFacts() {
        DatasetExecutionResult result = result(
                DatasetExecutionStatus.SUCCESS,
                Map.of(
                        "calculation", Map.of("employeeNo", "E1001"),
                        "display", Map.of(
                                "subjectCandidates", List.of(personCandidate()),
                                "totalCount", 1,
                                "hasNext", false
                        ),
                        "export", Map.of(),
                        "model", Map.of()
                )
        );
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        assertThat(service.search(query()).accessible()).isFalse();
    }

    @Test
    void failsClosedWhenCandidateOrPagingMetadataIsInvalid() {
        DatasetExecutionResult nonPerson = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(Map.of(
                                "subjectRef", "project-ref-1",
                                "displayName", "一号项目",
                                "projectCode", "P100"
                        )),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        DatasetExecutionResult rawEmployeeNo = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(Map.of(
                                "subjectRef", "person-ref-1",
                                "displayName", "张三",
                                "maskedEmployeeNo", "E1001"
                        )),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        DatasetExecutionResult inconsistentPaging = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(personCandidate()),
                        "totalCount", 2,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(
                nonPerson,
                rawEmployeeNo,
                inconsistentPaging
        );
        when(proofVerifier.verify(nonPerson)).thenReturn(true);
        when(proofVerifier.verify(rawEmployeeNo)).thenReturn(true);
        when(proofVerifier.verify(inconsistentPaging)).thenReturn(true);

        assertThat(service.search(query()).accessible()).isFalse();
        assertThat(service.search(query()).accessible()).isFalse();
        assertThat(service.search(query()).accessible()).isFalse();
    }

    @Test
    void queryToStringDoesNotLeakDepartmentAuthorizationOrSecureContext() {
        String value = query().toString();

        assertThat(value)
                .contains(
                        "authorizationPresent=true",
                        "secureContextPresent=true",
                        "pageNumber=1",
                        "pageSize=20"
                )
                .doesNotContain(
                        "Bearer department-secret",
                        "department-context",
                        "department-ref-1",
                        "login-user-1",
                        "session-1"
                );
    }

    private WorkflowBackedDepartmentMemberDirectoryService service(String datasetCode) {
        return new WorkflowBackedDepartmentMemberDirectoryService(
                executionService,
                proofVerifier,
                datasetMapper,
                datasetFieldMapper,
                new ObjectMapper(),
                datasetCode
        );
    }

    private DepartmentMemberDirectoryQuery query() {
        return new DepartmentMemberDirectoryQuery(
                "agent-run-1",
                "login-user-1",
                "session-1",
                "Bearer department-secret",
                Map.of("tenant", "department-context"),
                "department-ref-1",
                1,
                20
        );
    }

    private DatasetExecutionResult result(
            DatasetExecutionStatus status,
            Map<String, Object> safeFacts) {
        return result(source("login-user-1", canonicalHash()), status, safeFacts);
    }

    private DatasetExecutionResult result(
            DatasetExecutionSource source,
            DatasetExecutionStatus status,
            Map<String, Object> safeFacts) {
        return new DatasetExecutionResult(
                source,
                status,
                status == DatasetExecutionStatus.SUCCESS,
                safeFacts,
                "run-1",
                null,
                null,
                "safe",
                "proof"
        );
    }

    private DatasetExecutionSource source(String userId, String canonicalInputHash) {
        return new DatasetExecutionSource(
                userId,
                "session-1",
                BusinessSubjectType.PERSON,
                "login-user-1",
                DATASET_CODE,
                canonicalInputHash,
                "query-workflow",
                10L,
                "a".repeat(64),
                "b".repeat(64)
        );
    }

    private String canonicalHash() {
        return ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(Map.of(
                        "departmentSubjectId", "department-ref-1",
                        "pageNumber", 1,
                        "pageSize", 20
                ))
        );
    }

    private Map<String, Object> personCandidate() {
        return Map.of(
                "subjectRef", "person-ref-1",
                "displayName", "张三",
                "maskedEmployeeNo", "E***01",
                "departmentPath", "集团/工程部"
        );
    }

    private Map<String, Object> envelope(Map<String, Object> display) {
        return Map.of(
                "calculation", Map.of(),
                "display", display,
                "export", Map.of(),
                "model", Map.of()
        );
    }

    @SuppressWarnings("unchecked")
    private void arrangeDirectoryContract() {
        ReportDataset dataset = new ReportDataset();
        dataset.setId(1L);
        dataset.setDatasetCode(DATASET_CODE);
        dataset.setSubjectTypesJson("[\"PERSON\"]");
        dataset.setInputMappingJson("""
                {
                  "access":{"departmentSubjectId":"department_id"},
                  "query":{
                    "departmentSubjectId":"department_id",
                    "pageNumber":"page_number",
                    "pageSize":"page_size"
                  }
                }
                """);
        dataset.setConfigChecksum("a".repeat(64));
        dataset.setFieldPolicyChecksum("b".repeat(64));
        dataset.setEnabled(true);
        org.mockito.Mockito.lenient()
                .when(datasetMapper.selectOne(any(Wrapper.class)))
                .thenReturn(dataset);
        org.mockito.Mockito.lenient()
                .when(datasetFieldMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(
                        directoryField("subjectCandidates"),
                        directoryField("totalCount"),
                        directoryField("hasNext")
                ));
    }

    private ReportDatasetField directoryField(String factCode) {
        ReportDatasetField field = new ReportDatasetField();
        field.setDatasetId(1L);
        field.setFactCode(factCode);
        field.setDisplayable(true);
        field.setCalculable(false);
        field.setExportable(false);
        field.setModelVisible(false);
        return field;
    }
}
