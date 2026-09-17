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
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.model.ProjectRelationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowBackedSubjectDirectoryServiceTest {

    @Mock
    private ReportDatasetExecutionService executionService;
    @Mock
    private DatasetExecutionProofVerifier proofVerifier;
    @Mock
    private ReportDatasetMapper datasetMapper;
    @Mock
    private ReportDatasetFieldMapper datasetFieldMapper;

    private WorkflowBackedSubjectDirectoryService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowBackedSubjectDirectoryService(
                executionService,
                proofVerifier,
                datasetMapper,
                datasetFieldMapper,
                new ObjectMapper(),
                "PROJECT_DIRECTORY",
                "PERSON_DIRECTORY",
                "DEPARTMENT_DIRECTORY"
        );
        arrangeDirectoryContract();
    }

    @Test
    void executesConfiguredDirectoryDatasetWithCurrentAuthorizationAndPaging() {
        DatasetExecutionResult result = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(Map.of(
                                "subjectRef", "project-id-1",
                                "displayName", "一号项目",
                                "projectCode", "P100",
                                "projectType", "工程项目"
                        )),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        SubjectDirectoryPage page = service.search(projectQuery());

        ArgumentCaptor<DatasetExecutionRequest> request = ArgumentCaptor.forClass(DatasetExecutionRequest.class);
        verify(executionService).execute(request.capture());
        assertThat(request.getValue().datasetCode()).isEqualTo("PROJECT_DIRECTORY");
        assertThat(request.getValue().subjectType()).isEqualTo(BusinessSubjectType.PERSON);
        assertThat(request.getValue().subjectId()).isEqualTo("login-user-1");
        assertThat(request.getValue().authorization()).isEqualTo("Bearer secret");
        assertThat(request.getValue().secureContext()).containsEntry("tenant", "secret");
        assertThat(request.getValue().canonicalInput()).containsEntry("projectManager", "张经理");
        assertThat(request.getValue().canonicalInput()).containsEntry("projectYear", 2026);
        assertThat(request.getValue().canonicalInput()).containsEntry("pageNumber", 1);
        assertThat(request.getValue().canonicalInput()).containsEntry("pageSize", 50);
        assertThat(page.totalCount()).isEqualTo(1);
        assertThat(page.hasNext()).isFalse();
        assertThat(page.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.rawSubjectId()).isEqualTo("project-id-1");
            assertThat(candidate.projectCode()).isEqualTo("P100");
            assertThat(candidate.toString()).doesNotContain("project-id-1");
        });
    }

    /**
     * “我的项目”只接受负责或参与关系，并允许 PM 不提供可靠总数。
     */
    @Test
    void parsesMyProjectRelationshipsWithoutInventingTotal() {
        SubjectDirectoryQuery query = myProjectsQuery();
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("subjectCandidates", List.of(
                projectCandidate("project-1", "P100", "RESPONSIBLE"),
                projectCandidate("project-2", "P200", "PARTICIPATING")
        ));
        display.put("totalCount", null);
        display.put("hasNext", true);
        DatasetExecutionResult result = result(query, DatasetExecutionStatus.SUCCESS, envelope(display));
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        SubjectDirectoryPage page = service.search(query);

        assertThat(page.accessible()).isTrue();
        assertThat(page.totalKnown()).isFalse();
        assertThat(page.totalCount()).isZero();
        assertThat(page.hasNext()).isTrue();
        assertThat(page.candidates()).extracting("projectRelationship")
                .containsExactly(ProjectRelationship.RESPONSIBLE, ProjectRelationship.PARTICIPATING);
        assertThat(page.candidates()).extracting("projectStatus").containsOnly("进行中");
    }

    /**
     * PM 即使错误返回可查看关系，默认“我的项目”也必须失败关闭。
     */
    @Test
    void rejectsViewableRelationshipFromMyProjectsScope() {
        SubjectDirectoryQuery query = myProjectsQuery();
        DatasetExecutionResult result = result(query, DatasetExecutionStatus.SUCCESS, envelope(Map.of(
                "subjectCandidates", List.of(projectCandidate("project-1", "P100", "VIEWABLE")),
                "totalCount", 1,
                "hasNext", false
        )));
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        SubjectDirectoryPage page = service.search(query);

        assertThat(page.accessible()).isFalse();
        assertThat(page.candidates()).isEmpty();
    }

    @Test
    void acceptsOnlyAlreadyMaskedEmployeeNumberFromDisplayFacts() {
        DatasetExecutionResult result = result(
                BusinessSubjectType.PERSON,
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(Map.of(
                                "subjectRef", "opaque-person-id",
                                "displayName", "张三",
                                "maskedEmployeeNo", "E***01",
                                "departmentPath", "集团/工程部"
                        )),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        SubjectDirectoryPage page = service.search(personQuery());

        assertThat(page.accessible()).isTrue();
        assertThat(page.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.type()).isEqualTo(BusinessSubjectType.PERSON);
            assertThat(candidate.maskedEmployeeNo()).isEqualTo("E***01");
            assertThat(candidate.toString())
                    .doesNotContain("opaque-person-id", "张三", "E***01", "集团/工程部");
        });
    }

    @Test
    void failsClosedWhenConfiguredDatasetCodeIsMissing() {
        service = new WorkflowBackedSubjectDirectoryService(
                executionService,
                proofVerifier,
                datasetMapper,
                datasetFieldMapper,
                new ObjectMapper(),
                " ",
                "PERSON_DIRECTORY",
                "DEPARTMENT_DIRECTORY"
        );

        SubjectDirectoryPage page = service.search(projectQuery());

        assertThat(page.accessible()).isFalse();
        verify(executionService, never()).execute(any());
    }

    @Test
    void failsClosedForDeniedFailedUnsignedOrMalformedResults() {
        DatasetExecutionResult denied = result(DatasetExecutionStatus.DENIED, Map.of());
        when(executionService.execute(any())).thenReturn(denied);
        when(proofVerifier.verify(denied)).thenReturn(true);
        assertThat(service.search(projectQuery()).accessible()).isFalse();

        DatasetExecutionResult unsigned = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(),
                        "totalCount", 0,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(unsigned);
        when(proofVerifier.verify(unsigned)).thenReturn(false);
        assertThat(service.search(projectQuery()).accessible()).isFalse();

        DatasetExecutionResult rawEmployeeNumber = result(
                BusinessSubjectType.PERSON,
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(Map.of(
                                "subjectRef", "E1001",
                                "displayName", "张三",
                                "maskedEmployeeNo", "E1001"
                        )),
                        "totalCount", 1,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(rawEmployeeNumber);
        when(proofVerifier.verify(rawEmployeeNumber)).thenReturn(true);
        assertThat(service.search(personQuery()).accessible()).isFalse();
    }

    @Test
    void personNameSearchDoesNotSendAnEmployeeNumberOrQueryPersonDetails() {
        DatasetExecutionResult result = result(
                BusinessSubjectType.PERSON,
                DatasetExecutionStatus.EMPTY,
                envelope(Map.of())
        );
        when(executionService.execute(any())).thenReturn(result);
        when(proofVerifier.verify(result)).thenReturn(true);

        service.search(personQuery());

        ArgumentCaptor<DatasetExecutionRequest> request = ArgumentCaptor.forClass(DatasetExecutionRequest.class);
        verify(executionService).execute(request.capture());
        assertThat(request.getValue().canonicalInput())
                .containsEntry("searchName", "张三")
                .doesNotContainKey("employeeNo");
    }

    @Test
    void rejectsAValidProofThatBelongsToDifferentCanonicalDirectoryInput() {
        DatasetExecutionResult mismatched = new DatasetExecutionResult(
                new DatasetExecutionSource(
                        "login-user-1",
                        "session-1",
                        BusinessSubjectType.PERSON,
                        "login-user-1",
                        "PROJECT_DIRECTORY",
                        "d".repeat(64),
                        "query-workflow",
                        10L,
                        "a".repeat(64),
                        "b".repeat(64)
                ),
                DatasetExecutionStatus.SUCCESS,
                true,
                envelope(Map.of(
                        "subjectCandidates", List.of(Map.of(
                                "subjectRef", "project-id-1",
                                "displayName", "一号项目",
                                "projectCode", "P100"
                        )),
                        "totalCount", 1,
                        "hasNext", false
                )),
                "run-1",
                null,
                null,
                "safe",
                "proof"
        );
        when(executionService.execute(any())).thenReturn(mismatched);
        when(proofVerifier.verify(mismatched)).thenReturn(true);

        assertThat(service.search(projectQuery()).accessible()).isFalse();
    }

    @Test
    void rejectsSignedCandidatesWhosePagingMetadataIsInconsistent() {
        DatasetExecutionResult inconsistent = result(
                DatasetExecutionStatus.SUCCESS,
                envelope(Map.of(
                        "subjectCandidates", List.of(Map.of(
                                "subjectRef", "project-id-1",
                                "displayName", "一号项目",
                                "projectCode", "P100"
                        )),
                        "totalCount", 2,
                        "hasNext", false
                ))
        );
        when(executionService.execute(any())).thenReturn(inconsistent);
        when(proofVerifier.verify(inconsistent)).thenReturn(true);

        assertThat(service.search(projectQuery()).accessible()).isFalse();
    }

    @Test
    void candidateModelRejectsRawEmployeeNumberAndCrossTypeFields() {
        assertThatThrownBy(() -> new SubjectCandidate(
                BusinessSubjectType.PERSON,
                "person-id",
                "张三",
                "E1001",
                "工程部",
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SubjectCandidate(
                BusinessSubjectType.DEPARTMENT,
                "department-id",
                "工程部",
                null,
                "集团/工程部",
                "P100",
                null
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private SubjectDirectoryQuery projectQuery() {
        return new SubjectDirectoryQuery(
                "agent-run-1",
                "login-user-1",
                "session-1",
                "Bearer secret",
                Map.of("tenant", "secret"),
                BusinessSubjectType.PROJECT,
                SubjectSearchMode.PROJECT_MANAGER,
                null,
                null,
                null,
                "张经理",
                2026,
                null,
                1,
                50
        );
    }

    private SubjectDirectoryQuery myProjectsQuery() {
        return new SubjectDirectoryQuery(
                "agent-run-1", "login-user-1", "session-1", "Bearer secret",
                Map.of("tenant", "secret"), BusinessSubjectType.PROJECT,
                SubjectSearchMode.MY_PROJECTS, null, null, null, null, 2026, null, 1, 2
        );
    }

    private SubjectDirectoryQuery personQuery() {
        return new SubjectDirectoryQuery(
                "agent-run-1",
                "login-user-1",
                "session-1",
                "Bearer secret",
                Map.of("tenant", "secret"),
                BusinessSubjectType.PERSON,
                SubjectSearchMode.PERSON_NAME,
                null,
                null,
                "张三",
                null,
                null,
                null,
                1,
                20
        );
    }

    private DatasetExecutionResult result(
            DatasetExecutionStatus status,
            Map<String, Object> safeFacts) {
        return result(BusinessSubjectType.PROJECT, status, safeFacts);
    }

    private DatasetExecutionResult result(
            BusinessSubjectType subjectType,
            DatasetExecutionStatus status,
            Map<String, Object> safeFacts) {
        String datasetCode = subjectType == BusinessSubjectType.PROJECT
                ? "PROJECT_DIRECTORY"
                : subjectType == BusinessSubjectType.PERSON
                ? "PERSON_DIRECTORY"
                : "DEPARTMENT_DIRECTORY";
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        "login-user-1",
                        "session-1",
                        BusinessSubjectType.PERSON,
                        "login-user-1",
                        datasetCode,
                        canonicalHash(subjectType),
                        "query-workflow",
                        10L,
                        "a".repeat(64),
                        "b".repeat(64)
                ),
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

    private DatasetExecutionResult result(
            SubjectDirectoryQuery query,
            DatasetExecutionStatus status,
            Map<String, Object> safeFacts) {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        query.userId(), query.sessionId(), BusinessSubjectType.PERSON, query.userId(),
                        "PROJECT_DIRECTORY", canonicalHash(query), "query-workflow", 10L,
                        "a".repeat(64), "b".repeat(64)
                ),
                status, status == DatasetExecutionStatus.SUCCESS, safeFacts,
                "run-1", null, null, "safe", "proof"
        );
    }

    private String canonicalHash(SubjectDirectoryQuery query) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("searchMode", query.searchMode().name());
        putIfPresent(input, "selectedSubjectId", query.selectedSubjectId());
        putIfPresent(input, "projectCode", query.projectCode());
        putIfPresent(input, "searchName", query.searchName());
        putIfPresent(input, "projectManager", query.projectManager());
        putIfPresent(input, "projectYear", query.projectYear());
        putIfPresent(input, "employeeNo", query.employeeNo());
        input.put("pageNumber", query.pageNumber());
        input.put("pageSize", query.pageSize());
        return ContentHashUtils.sha256(ReportDatasetValidator.canonicalSafeValue(input));
    }

    private Map<String, Object> projectCandidate(String id, String code, String relationship) {
        return Map.of(
                "subjectRef", id,
                "displayName", code + " 项目",
                "projectCode", code,
                "projectType", "DELIVERY",
                "projectRelationship", relationship,
                "projectStatus", "进行中"
        );
    }

    private void putIfPresent(Map<String, Object> input, String key, Object value) {
        if (value != null) {
            input.put(key, value);
        }
    }

    private String canonicalHash(BusinessSubjectType subjectType) {
        Map<String, Object> canonicalInput;
        if (subjectType == BusinessSubjectType.PROJECT) {
            canonicalInput = Map.of(
                    "searchMode", "PROJECT_MANAGER",
                    "projectManager", "张经理",
                    "projectYear", 2026,
                    "pageNumber", 1,
                    "pageSize", 50
            );
        } else {
            canonicalInput = Map.of(
                    "searchMode", "PERSON_NAME",
                    "searchName", "张三",
                    "pageNumber", 1,
                    "pageSize", 20
            );
        }
        return ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(canonicalInput)
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
        dataset.setDatasetCode("PROJECT_DIRECTORY");
        dataset.setSubjectTypesJson("[\"PERSON\"]");
        dataset.setInputMappingJson("""
                {
                  "access":{"searchMode":"mode"},
                  "query":{
                    "searchMode":"mode",
                    "selectedSubjectId":"selected_id",
                    "projectCode":"project_code",
                    "searchName":"search_name",
                    "projectManager":"manager",
                    "projectYear":"project_year",
                    "employeeNo":"employee_no",
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
                .thenAnswer(invocation -> dataset);
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
