package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectResolutionServiceTest {

    private static final String SAFE_NOT_FOUND = "无法定位或无权访问该主体";

    @Mock
    private ProjectDirectoryService projectDirectoryService;
    @Mock
    private AuthorizedPersonDirectoryService personDirectoryService;
    @Mock
    private DepartmentDirectoryService departmentDirectoryService;

    private SubjectResolutionService service;

    @BeforeEach
    void setUp() {
        service = new SubjectResolutionService(
                projectDirectoryService,
                personDirectoryService,
                departmentDirectoryService,
                Clock.fixed(
                        Instant.parse("2026-09-03T00:00:00Z"),
                        ZoneId.of("Asia/Shanghai")
                )
        );
    }

    @Test
    void resolvesAnExactAuthorizedProjectCodeWithoutCallingOtherDirectories() {
        SubjectCandidate project = project("project-id-1", "XXXT2674040", "工程项目");
        when(projectDirectoryService.search(any())).thenReturn(page(project));

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PROJECT,
                null,
                "XXXT2674040",
                null,
                null,
                null,
                false,
                null,
                1,
                20
        ));

        assertThat(result.state()).isEqualTo(SubjectResolutionState.RESOLVED);
        assertThat(result.resolvedSubject()).isEqualTo(project);
        verify(personDirectoryService, never()).search(any());
        verify(departmentDirectoryService, never()).search(any());
    }

    @Test
    void returnsPagedProjectCandidatesForNameOrManagerAndNeverAutoSelects() {
        SubjectCandidate first = project("project-id-1", "P100", "工程项目");
        SubjectCandidate second = project("project-id-2", "P200", "维护项目");
        when(projectDirectoryService.search(any())).thenReturn(
                new SubjectDirectoryPage(true, List.of(first, second), 2, 20, 67, true)
        );

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PROJECT,
                null,
                null,
                "轨道项目",
                "张经理",
                2025,
                false,
                null,
                2,
                20
        ));

        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.resolvedSubject()).isNull();
        assertThat(result.candidates()).containsExactly(first, second);
        assertThat(result.totalCount()).isEqualTo(67);
        assertThat(result.hasNext()).isTrue();
    }

    @Test
    void defaultsMyProjectsToCurrentProjectYearAndKeepsMoreThanTwoHundredPaged() {
        when(projectDirectoryService.search(any())).thenReturn(
                new SubjectDirectoryPage(
                        true,
                        List.of(project("project-id-1", "P100", "工程项目")),
                        1,
                        50,
                        236,
                        true
                )
        );

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PROJECT,
                null,
                null,
                null,
                null,
                null,
                true,
                null,
                1,
                50
        ));

        ArgumentCaptor<SubjectDirectoryQuery> query = ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(projectDirectoryService).search(query.capture());
        assertThat(query.getValue().searchMode()).isEqualTo(SubjectSearchMode.MY_PROJECTS);
        assertThat(query.getValue().projectYear()).isEqualTo(2026);
        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.totalCount()).isEqualTo(236);
        verify(personDirectoryService, never()).search(any());
        verify(departmentDirectoryService, never()).search(any());
    }

    @Test
    void resolvesCurrentEmployeeOnlyFromSourceAuthorizedDirectory() {
        SubjectCandidate self = person("E1001", "李四", "研发中心/平台组", "E***01");
        when(personDirectoryService.search(any())).thenReturn(page(self));

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PERSON,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                1,
                20
        ));

        ArgumentCaptor<SubjectDirectoryQuery> query = ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(personDirectoryService).search(query.capture());
        assertThat(query.getValue().searchMode()).isEqualTo(SubjectSearchMode.CURRENT_PERSON);
        assertThat(result.state()).isEqualTo(SubjectResolutionState.RESOLVED);
        assertThat(result.resolvedSubject()).isEqualTo(self);
    }

    @Test
    void directEmployeeNumberStillUsesAuthorizedDirectoryBeforeResolution() {
        SubjectCandidate employee = person("E9001", "王五", "财务中心", "E***01");
        when(personDirectoryService.search(any())).thenReturn(page(employee));

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PERSON,
                null,
                null,
                null,
                null,
                null,
                false,
                "E9001",
                1,
                20
        ));

        ArgumentCaptor<SubjectDirectoryQuery> query = ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(personDirectoryService).search(query.capture());
        assertThat(query.getValue().searchMode()).isEqualTo(SubjectSearchMode.EMPLOYEE_NO);
        assertThat(query.getValue().employeeNo()).isEqualTo("E9001");
        assertThat(result.state()).isEqualTo(SubjectResolutionState.RESOLVED);
    }

    @Test
    void duplicatePersonNamesRequireExplicitSelectionFromMaskedCandidates() {
        SubjectCandidate first = person("E1001", "张三", "工程部/一组", "E***01");
        SubjectCandidate second = person("E2001", "张三", "工程部/二组", "E***01");
        when(personDirectoryService.search(any())).thenReturn(
                new SubjectDirectoryPage(true, List.of(first, second), 1, 20, 2, false)
        );

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PERSON,
                null,
                null,
                "张三",
                null,
                null,
                false,
                null,
                1,
                20
        ));

        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.resolvedSubject()).isNull();
        assertThat(result.candidates()).containsExactly(first, second);
        assertThat(result.candidates()).allSatisfy(candidate ->
                assertThat(candidate.maskedEmployeeNo()).contains("***")
        );
    }

    @Test
    void neverTreatsTheFirstPageItemAsUniqueWhenDirectoryTotalIsGreaterThanOne() {
        SubjectCandidate first = person("E1001", "张三", "工程部/一组", "E***01");
        when(personDirectoryService.search(any())).thenReturn(
                new SubjectDirectoryPage(true, List.of(first), 1, 1, 2, true)
        );

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PERSON,
                null,
                null,
                "张三",
                null,
                null,
                false,
                null,
                1,
                1
        ));

        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.resolvedSubject()).isNull();
        assertThat(result.totalCount()).isEqualTo(2);
    }

    @Test
    void selectedSubjectIdIsRevalidatedAgainstCurrentAuthorizedDirectory() {
        SubjectCandidate selected = person("opaque-person-id", "张三", "工程部", "E***01");
        when(personDirectoryService.search(any())).thenReturn(page(selected));

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PERSON,
                "opaque-person-id",
                null,
                null,
                null,
                null,
                false,
                null,
                1,
                20
        ));

        ArgumentCaptor<SubjectDirectoryQuery> query = ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(personDirectoryService).search(query.capture());
        assertThat(query.getValue().searchMode()).isEqualTo(SubjectSearchMode.SELECTED_SUBJECT);
        assertThat(query.getValue().selectedSubjectId()).isEqualTo("opaque-person-id");
        assertThat(result.state()).isEqualTo(SubjectResolutionState.RESOLVED);
    }

    @Test
    void departmentResolutionAlsoUsesSourceAuthorizedDirectory() {
        SubjectCandidate department = new SubjectCandidate(
                BusinessSubjectType.DEPARTMENT,
                "department-id-1",
                "工程部",
                null,
                "集团/工程部",
                null,
                null
        );
        when(departmentDirectoryService.search(any())).thenReturn(page(department));

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.DEPARTMENT,
                null,
                null,
                "工程部",
                null,
                null,
                false,
                null,
                1,
                20
        ));

        verify(departmentDirectoryService).search(any());
        assertThat(result.state()).isEqualTo(SubjectResolutionState.RESOLVED);
        assertThat(result.resolvedSubject()).isEqualTo(department);
    }

    @Test
    void deniedAndEmptyUseTheSameExternalSafeMessage() {
        when(projectDirectoryService.search(any())).thenReturn(SubjectDirectoryPage.denied());
        SubjectResolutionResult denied = service.resolve(request(
                BusinessSubjectType.PROJECT, null, "P404", null, null,
                2026, false, null, 1, 20
        ));

        when(projectDirectoryService.search(any())).thenReturn(SubjectDirectoryPage.empty(1, 20));
        SubjectResolutionResult empty = service.resolve(request(
                BusinessSubjectType.PROJECT, null, "P404", null, null,
                2026, false, null, 1, 20
        ));

        assertThat(denied.state()).isEqualTo(SubjectResolutionState.DENIED);
        assertThat(empty.state()).isEqualTo(SubjectResolutionState.EMPTY);
        assertThat(denied.safeMessage()).isEqualTo(SAFE_NOT_FOUND);
        assertThat(empty.safeMessage()).isEqualTo(SAFE_NOT_FOUND);
        assertThat(denied.candidates()).isEmpty();
        assertThat(empty.candidates()).isEmpty();
    }

    @Test
    void failsClosedWhenCurrentPersonDirectoryUnexpectedlyReturnsMultiplePeople() {
        when(personDirectoryService.search(any())).thenReturn(
                new SubjectDirectoryPage(
                        true,
                        List.of(
                                person("E1001", "李四", "研发一组", "E***01"),
                                person("E2001", "王五", "研发二组", "E***01")
                        ),
                        1,
                        20,
                        2,
                        false
                )
        );

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PERSON,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                1,
                20
        ));

        assertThat(result.state()).isEqualTo(SubjectResolutionState.DENIED);
        assertThat(result.safeMessage()).isEqualTo(SAFE_NOT_FOUND);
    }

    @Test
    void rejectsCrossSubjectOrConflictingSearchHintsBeforeDirectoryExecution() {
        assertThatThrownBy(() -> service.resolve(request(
                BusinessSubjectType.PERSON,
                null,
                "P100",
                "张三",
                null,
                null,
                false,
                null,
                1,
                20
        ))).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.resolve(request(
                BusinessSubjectType.PROJECT,
                "project-id-1",
                "P100",
                null,
                null,
                2026,
                false,
                null,
                1,
                20
        ))).isInstanceOf(IllegalArgumentException.class);

        verify(projectDirectoryService, never()).search(any());
        verify(personDirectoryService, never()).search(any());
        verify(departmentDirectoryService, never()).search(any());
    }

    private SubjectResolutionRequest request(
            BusinessSubjectType type,
            String selectedSubjectId,
            String projectCode,
            String searchName,
            String projectManager,
            Integer projectYear,
            boolean myProjects,
            String employeeNo,
            int pageNumber,
            int pageSize) {
        return new SubjectResolutionRequest(
                "agent-run-1",
                "login-user-1",
                "session-1",
                "Bearer secret",
                Map.of("tenantToken", "secret"),
                type,
                selectedSubjectId,
                projectCode,
                searchName,
                projectManager,
                projectYear,
                myProjects,
                employeeNo,
                pageNumber,
                pageSize
        );
    }

    private SubjectDirectoryPage page(SubjectCandidate candidate) {
        return new SubjectDirectoryPage(true, List.of(candidate), 1, 20, 1, false);
    }

    private SubjectCandidate project(String id, String code, String type) {
        return new SubjectCandidate(
                BusinessSubjectType.PROJECT,
                id,
                "项目" + code,
                null,
                null,
                code,
                type
        );
    }

    private SubjectCandidate person(
            String id,
            String name,
            String department,
            String maskedEmployeeNo) {
        return new SubjectCandidate(
                BusinessSubjectType.PERSON,
                id,
                name,
                maskedEmployeeNo,
                department,
                null,
                null
        );
    }
}
