package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.common.model.ProjectListScope;
import org.example.ai.agent.common.model.ProjectRelationship;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
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
    private SubjectSelectionTokenService tokenService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-03T00:00:00Z"),
                ZoneId.of("Asia/Shanghai")
        );
        tokenService = new SubjectSelectionTokenService(
                "0123456789abcdef0123456789abcdef".getBytes(),
                clock,
                Duration.ofMinutes(10)
        );
        service = new SubjectResolutionService(
                projectDirectoryService,
                personDirectoryService,
                departmentDirectoryService,
                tokenService,
                clock
        );
    }

    @Test
    void resolvesAnExactAuthorizedProjectCodeWithoutCallingOtherDirectories() {
        AuthorizedSubjectCandidate project = project("project-id-1", "XXXT2674040", "工程项目");
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
        assertThat(result.resolvedSubject().projectCode()).isEqualTo("XXXT2674040");
        assertThat(result.resolvedSubject().selectionToken()).doesNotContain("project-id-1");
        verify(personDirectoryService, never()).search(any());
        verify(departmentDirectoryService, never()).search(any());
    }

    @Test
    void returnsPagedProjectCandidatesForNameOrManagerAndNeverAutoSelects() {
        AuthorizedSubjectCandidate first = project("project-id-1", "P100", "工程项目");
        AuthorizedSubjectCandidate second = project("project-id-2", "P200", "维护项目");
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
        assertThat(result.candidates())
                .extracting(candidate -> candidate.projectCode())
                .containsExactly("P100", "P200");
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

    /**
     * “可查看项目”必须使用独立查询范围，不能降级成默认“我的项目”。
     */
    @Test
    void routesExplicitViewableProjectsToIndependentSearchMode() {
        AuthorizedSubjectCandidate candidate = project(
                "project-id-1", "P100", "工程项目", ProjectRelationship.VIEWABLE, "进行中"
        );
        when(projectDirectoryService.search(any())).thenReturn(
                new SubjectDirectoryPage(true, List.of(candidate), 1, 10, 1, true, false)
        );

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PROJECT, null, null, null, null, null,
                ProjectListScope.VIEWABLE_PROJECTS, null, 1, 10
        ));

        ArgumentCaptor<SubjectDirectoryQuery> query = ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(projectDirectoryService).search(query.capture());
        assertThat(query.getValue().searchMode()).isEqualTo(SubjectSearchMode.VIEWABLE_PROJECTS);
        assertThat(query.getValue().projectYear()).isEqualTo(2026);
        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.candidates()).singleElement().satisfies(project -> {
            assertThat(project.projectRelationship()).isEqualTo(ProjectRelationship.VIEWABLE);
            assertThat(project.projectStatus()).isEqualTo("进行中");
        });
    }

    /**
     * PM 未返回可靠总数时，只透传 hasNext，不能把当前页数量伪造成总数。
     */
    @Test
    void keepsUnknownProjectTotalWithoutAutoResolvingFirstCandidate() {
        AuthorizedSubjectCandidate candidate = project(
                "project-id-1", "P100", "工程项目", ProjectRelationship.RESPONSIBLE, "进行中"
        );
        when(projectDirectoryService.search(any())).thenReturn(
                SubjectDirectoryPage.unknownTotal(List.of(candidate), 1, 10, true)
        );

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PROJECT, null, null, null, null, null,
                ProjectListScope.MY_PROJECTS, null, 1, 10
        ));

        assertThat(result.state()).isEqualTo(SubjectResolutionState.CANDIDATES);
        assertThat(result.totalKnown()).isFalse();
        assertThat(result.totalCount()).isZero();
        assertThat(result.hasNext()).isTrue();
        assertThat(result.resolvedSubject()).isNull();
    }

    @Test
    void resolvesCurrentEmployeeOnlyFromSourceAuthorizedDirectory() {
        AuthorizedSubjectCandidate self = person("E1001", "李四", "研发中心/平台组", "E***01");
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
        assertThat(result.resolvedSubject().displayName()).isEqualTo("李四");
    }

    @Test
    void directEmployeeNumberStillUsesAuthorizedDirectoryBeforeResolution() {
        AuthorizedSubjectCandidate employee = person("E9001", "王五", "财务中心", "E***01");
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
        AuthorizedSubjectCandidate first = person("E1001", "张三", "工程部/一组", "E***01");
        AuthorizedSubjectCandidate second = person("E2001", "张三", "工程部/二组", "E***01");
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
        assertThat(result.candidates()).extracting(candidate -> candidate.departmentPath())
                .containsExactly("工程部/一组", "工程部/二组");
        assertThat(result.candidates()).allSatisfy(candidate ->
                assertThat(candidate.maskedEmployeeNo()).contains("***")
        );
    }

    @Test
    void neverTreatsTheFirstPageItemAsUniqueWhenDirectoryTotalIsGreaterThanOne() {
        AuthorizedSubjectCandidate first = person("E1001", "张三", "工程部/一组", "E***01");
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
        AuthorizedSubjectCandidate selected = person("opaque-person-id", "张三", "工程部", "E***01");
        when(personDirectoryService.search(any())).thenReturn(page(selected));

        SubjectResolutionResult result = service.resolve(request(
                BusinessSubjectType.PERSON,
                tokenService.issue(
                        "opaque-person-id",
                        "login-user-1",
                        "session-1",
                        BusinessSubjectType.PERSON
                ),
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
        AuthorizedSubjectCandidate department = new AuthorizedSubjectCandidate(
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
        assertThat(result.resolvedSubject().displayName()).isEqualTo("工程部");
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
            String selectionToken,
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
                selectionToken,
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

    private SubjectResolutionRequest request(
            BusinessSubjectType type,
            String selectionToken,
            String projectCode,
            String searchName,
            String projectManager,
            Integer projectYear,
            ProjectListScope projectListScope,
            String employeeNo,
            int pageNumber,
            int pageSize) {
        return new SubjectResolutionRequest(
                "agent-run-1", "login-user-1", "session-1", "Bearer secret",
                Map.of("tenantToken", "secret"), type, selectionToken, projectCode, searchName,
                projectManager, projectYear, projectListScope, employeeNo, pageNumber, pageSize
        );
    }

    private SubjectDirectoryPage page(AuthorizedSubjectCandidate candidate) {
        return new SubjectDirectoryPage(true, List.of(candidate), 1, 20, 1, false);
    }

    private AuthorizedSubjectCandidate project(String id, String code, String type) {
        return new AuthorizedSubjectCandidate(
                BusinessSubjectType.PROJECT,
                id,
                "项目" + code,
                null,
                null,
                code,
                type
        );
    }

    private AuthorizedSubjectCandidate project(
            String id,
            String code,
            String type,
            ProjectRelationship relationship,
            String status) {
        return new AuthorizedSubjectCandidate(
                BusinessSubjectType.PROJECT, id, "项目" + code, null, null,
                code, type, relationship, status
        );
    }

    private AuthorizedSubjectCandidate person(
            String id,
            String name,
            String department,
            String maskedEmployeeNo) {
        return new AuthorizedSubjectCandidate(
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
