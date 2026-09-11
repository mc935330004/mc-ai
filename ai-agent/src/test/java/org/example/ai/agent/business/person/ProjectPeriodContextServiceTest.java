package org.example.ai.agent.business.person;

import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.subject.AuthorizedPersonDirectoryService;
import org.example.ai.agent.business.subject.ProjectDirectoryService;
import org.example.ai.agent.business.subject.SubjectDirectoryQuery;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectPeriodContextServiceTest {

    private static final String PERSON_TOKEN = "person-selection-secret";
    private static final String PROJECT_TOKEN = "project-selection-secret";
    private static final String EMPLOYEE_NO = "E-1001";
    private static final String PROJECT_ID = "P-1";
    private static final String PROJECT_CODE = "XXXT2674040";
    private static final String AUTHORIZATION = "Bearer login-user-secret";

    private SubjectSelectionTokenService tokenService;
    private AuthorizedPersonDirectoryService personDirectoryService;
    private ProjectDirectoryService projectDirectoryService;
    private ReportDatasetService datasetService;
    private ReportDatasetExecutionService executionService;
    private DatasetExecutionProofVerifier proofVerifier;
    private ProjectPeriodContextService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(SubjectSelectionTokenService.class);
        personDirectoryService = mock(AuthorizedPersonDirectoryService.class);
        projectDirectoryService = mock(ProjectDirectoryService.class);
        datasetService = mock(ReportDatasetService.class);
        executionService = mock(ReportDatasetExecutionService.class);
        proofVerifier = mock(DatasetExecutionProofVerifier.class);
        when(proofVerifier.verify(any())).thenReturn(true);
        service = new ProjectPeriodContextService(
                tokenService,
                personDirectoryService,
                projectDirectoryService,
                datasetService,
                executionService,
                proofVerifier
        );
        prepareAuthorizedPersonAndProject();
    }

    @Test
    void independentlyReauthorizesPersonAndProjectBeforeLoadingProjectPeriod() {
        when(datasetService.list()).thenReturn(List.of(
                dataset("project-base-config", " project_base ", true)
        ));
        when(executionService.execute(any())).thenAnswer(invocation ->
                result(invocation.getArgument(0), DatasetExecutionStatus.SUCCESS,
                        projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                                "2026-01-01", "2026-12-31"))
        );

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.READY);
        assertThat(result.context().projectId()).isEqualTo(PROJECT_ID);
        assertThat(result.context().projectCode()).isEqualTo(PROJECT_CODE);
        assertThat(result.context().periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(result.context().periodEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(result.context().membershipAvailable()).isFalse();
        assertThat(result.context().membershipPeriods()).isEmpty();
        assertThat(result.safeMessage()).isEqualTo(
                "项目成员数据源尚未配置，仅能判断项目直接关联记录"
        );

        ArgumentCaptor<SubjectDirectoryQuery> personQuery =
                ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        ArgumentCaptor<SubjectDirectoryQuery> projectQuery =
                ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(personDirectoryService).search(personQuery.capture());
        verify(projectDirectoryService).search(projectQuery.capture());
        assertDirectoryQuery(personQuery.getValue(), BusinessSubjectType.PERSON, EMPLOYEE_NO);
        assertDirectoryQuery(projectQuery.getValue(), BusinessSubjectType.PROJECT, PROJECT_ID);

        ArgumentCaptor<DatasetExecutionRequest> request =
                ArgumentCaptor.forClass(DatasetExecutionRequest.class);
        verify(executionService).execute(request.capture());
        assertThat(request.getValue().datasetCode()).isEqualTo("project-base-config");
        assertThat(request.getValue().authorization()).isEqualTo(AUTHORIZATION);
        assertThat(request.getValue().secureContext()).containsEntry("tenant", "t1");
        assertThat(request.getValue().subjectType()).isEqualTo(BusinessSubjectType.PROJECT);
        assertThat(request.getValue().subjectId()).isEqualTo(PROJECT_ID);
        assertThat(request.getValue().canonicalInput())
                .containsEntry("projectId", PROJECT_ID)
                .containsEntry("projectCode", PROJECT_CODE)
                .containsEntry("projectYear", 2026);
    }

    @Test
    void personDenialStopsBeforeProjectAuthorizationAndDatasetLookup() {
        when(personDirectoryService.search(any())).thenReturn(SubjectDirectoryPage.denied());

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.DENIED);
        assertThat(result.context()).isNull();
        verify(projectDirectoryService, never()).search(any());
        verify(datasetService, never()).list();
        verify(executionService, never()).execute(any());
    }

    @Test
    void projectDenialStopsBeforeDatasetExecution() {
        when(projectDirectoryService.search(any())).thenReturn(SubjectDirectoryPage.denied());

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.DENIED);
        assertThat(result.context()).isNull();
        verify(datasetService, never()).list();
        verify(executionService, never()).execute(any());
    }

    @Test
    void nonUniqueOrMismatchedProjectAuthorizationIsDenied() {
        when(projectDirectoryService.search(any())).thenReturn(new SubjectDirectoryPage(
                true,
                List.of(
                        candidate(BusinessSubjectType.PROJECT, PROJECT_ID, PROJECT_CODE),
                        candidate(BusinessSubjectType.PROJECT, "P-2", "PROJECT-2")
                ),
                1,
                2,
                2,
                false
        ));
        assertThat(service.resolve(command()).status())
                .isEqualTo(ProjectPeriodContextService.Status.DENIED);

        when(projectDirectoryService.search(any())).thenReturn(uniquePage(
                candidate(BusinessSubjectType.PROJECT, "P-OTHER", PROJECT_CODE)
        ));
        assertThat(service.resolve(command()).status())
                .isEqualTo(ProjectPeriodContextService.Status.DENIED);
        verify(executionService, never()).execute(any());
    }

    @Test
    void invalidOrWrongTypeSelectionTokenReturnsSameDeniedResult() {
        when(tokenService.resolve(PERSON_TOKEN, "user-1", "session-1", BusinessSubjectType.PERSON))
                .thenReturn(Optional.empty());

        ProjectPeriodContextService.Result expired = service.resolve(command());

        reset(tokenService, personDirectoryService, projectDirectoryService, datasetService,
                executionService);
        when(tokenService.resolve(PERSON_TOKEN, "user-1", "session-1", BusinessSubjectType.PERSON))
                .thenReturn(Optional.of(EMPLOYEE_NO));
        when(personDirectoryService.search(any())).thenReturn(uniquePage(
                candidate(BusinessSubjectType.PROJECT, EMPLOYEE_NO, PROJECT_CODE)
        ));
        ProjectPeriodContextService.Result wrongType = service.resolve(command());

        assertThat(expired.status()).isEqualTo(ProjectPeriodContextService.Status.DENIED);
        assertThat(wrongType.status()).isEqualTo(ProjectPeriodContextService.Status.DENIED);
        assertThat(expired.safeMessage()).isEqualTo(wrongType.safeMessage());
    }

    @Test
    void missingProjectBaseDatasetReturnsFriendlyUnavailableResult() {
        when(datasetService.list()).thenReturn(List.of(
                dataset("disabled-base", "PROJECT_BASE", false),
                dataset("travel", "TRAVEL", true)
        ));

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status())
                .isEqualTo(ProjectPeriodContextService.Status.PROJECT_DATASET_NOT_CONFIGURED);
        assertThat(result.safeMessage()).isEqualTo(
                "项目基础数据源尚未配置，暂时无法取得项目期间"
        );
        verify(executionService, never()).execute(any());
    }

    @Test
    void duplicateProjectBaseDatasetFailsClosed() {
        when(datasetService.list()).thenReturn(List.of(
                dataset("base-a", "PROJECT_BASE", true),
                dataset("base-b", "project_base", true)
        ));

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.FAILED);
        verify(executionService, never()).execute(any());
    }

    @Test
    void duplicateProjectMemberDatasetFailsClosedAfterValidBasePeriod() {
        when(datasetService.list()).thenReturn(List.of(
                dataset("base", "PROJECT_BASE", true),
                dataset("member-a", "PROJECT_MEMBER", true),
                dataset("member-b", "project_member", true)
        ));
        when(executionService.execute(any())).thenAnswer(invocation ->
                result(invocation.getArgument(0), DatasetExecutionStatus.SUCCESS,
                        projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                                "2026-01-01", "2026-12-31"))
        );

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.FAILED);
        verify(executionService, times(1)).execute(any());
    }

    @Test
    void missingMalformedOrReversedProjectDatesAreUnavailable() {
        for (Map<String, Object> base : List.of(
                projectBase("2026-01-01", null),
                projectBase("not-a-date", "2026-12-31"),
                projectBase("2026-12-31", "2026-01-01")
        )) {
            reset(datasetService, executionService);
            when(datasetService.list()).thenReturn(List.of(
                    dataset("base", "PROJECT_BASE", true)
            ));
            when(executionService.execute(any())).thenAnswer(invocation ->
                    result(invocation.getArgument(0), DatasetExecutionStatus.SUCCESS,
                            Map.of("calculation", Map.of(
                                    ProjectPeriodContextService.PROJECT_BASE_FACT, base
                            )))
            );

            ProjectPeriodContextService.Result result = service.resolve(command());

            assertThat(result.status())
                    .isEqualTo(ProjectPeriodContextService.Status.PERIOD_UNAVAILABLE);
            assertThat(result.context()).isNull();
        }
    }

    @Test
    void projectBaseFactRequiresAProjectIdentityButUsesAuthorizedIdentityInContext() {
        when(datasetService.list()).thenReturn(List.of(
                dataset("base", "PROJECT_BASE", true)
        ));
        when(executionService.execute(any())).thenAnswer(invocation ->
                result(invocation.getArgument(0), DatasetExecutionStatus.SUCCESS,
                        Map.of("calculation", Map.of(
                                ProjectPeriodContextService.PROJECT_BASE_FACT,
                                Map.of(
                                        "projectStartDate", "2026-01-01",
                                        "projectEndDate", "2026-12-31"
                                )
                        )))
        );

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status())
                .isEqualTo(ProjectPeriodContextService.Status.PERIOD_UNAVAILABLE);
    }

    @Test
    void projectBaseAcceptsOnlyIdentifiersConsistentWithAuthorizedProject() {
        for (Map<String, Object> base : List.of(
                projectBaseIdentity(PROJECT_ID, null),
                projectBaseIdentity(null, PROJECT_CODE),
                projectBaseIdentity(PROJECT_ID, PROJECT_CODE)
        )) {
            prepareBaseResult(base);

            ProjectPeriodContextService.Result result = service.resolve(command());

            assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.READY);
            assertThat(result.context().projectId()).isEqualTo(PROJECT_ID);
            assertThat(result.context().projectCode()).isEqualTo(PROJECT_CODE);
            reset(datasetService, executionService);
        }

        for (Map<String, Object> base : List.of(
                projectBaseIdentity("P-OTHER", null),
                projectBaseIdentity(null, "OTHER-CODE"),
                projectBaseIdentity(PROJECT_ID, "OTHER-CODE"),
                projectBaseIdentity("P-OTHER", PROJECT_CODE)
        )) {
            prepareBaseResult(base);

            ProjectPeriodContextService.Result result = service.resolve(command());

            assertThat(result.status())
                    .isEqualTo(ProjectPeriodContextService.Status.PERIOD_UNAVAILABLE);
            assertThat(result.context()).isNull();
            reset(datasetService, executionService);
        }
    }

    @Test
    void untrustedProjectBaseProofOrSourceFailsClosed() {
        when(datasetService.list()).thenReturn(List.of(dataset("base", "PROJECT_BASE", true)));
        when(executionService.execute(any())).thenAnswer(invocation ->
                result(invocation.getArgument(0), DatasetExecutionStatus.SUCCESS,
                        projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                                "2026-01-01", "2026-12-31"))
        );
        when(proofVerifier.verify(any())).thenReturn(false);
        assertThat(service.resolve(command()).status())
                .isEqualTo(ProjectPeriodContextService.Status.FAILED);

        when(proofVerifier.verify(any())).thenReturn(true);
        for (String tampering : List.of(
                "subjectType", "subjectId", "datasetCode", "canonicalInputHash",
                "userId", "sessionId"
        )) {
            doAnswer(invocation -> {
                DatasetExecutionRequest request = invocation.getArgument(0);
                return tamperedSourceResult(request, tampering);
            }).when(executionService).execute(any());

            ProjectPeriodContextService.Result result = service.resolve(command());

            assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.FAILED);
            assertThat(result.context()).isNull();
        }
    }

    @Test
    void untrustedMembershipProofKeepsOnlyDirectAssociation() {
        prepareBothDatasets();
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return "base".equals(request.datasetCode())
                    ? result(request, DatasetExecutionStatus.SUCCESS,
                    projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                            "2026-01-01", "2026-12-31"))
                    : result(request, DatasetExecutionStatus.SUCCESS, membershipFacts(List.of(
                    membership(EMPLOYEE_NO, PROJECT_ID, PROJECT_CODE,
                            "2026-02-01", null)
            )));
        });
        when(proofVerifier.verify(any())).thenAnswer(invocation -> {
            DatasetExecutionResult result = invocation.getArgument(0);
            return "base".equals(result.datasetCode());
        });

        assertDirectOnlyFailure(service.resolve(command()));
    }

    @Test
    void parsesOnlyAuthorizedMembershipPeriodsAndOverridesClientIdentity() {
        when(datasetService.list()).thenReturn(List.of(
                dataset("base-custom", "PROJECT_BASE", true),
                dataset("member-custom", "PROJECT_MEMBER", true)
        ));
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            if ("base-custom".equals(request.datasetCode())) {
                return result(request, DatasetExecutionStatus.SUCCESS,
                        projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                                "2026-01-01", "2026-12-31"));
            }
            return result(request, DatasetExecutionStatus.SUCCESS, membershipFacts(List.of(
                    membership(EMPLOYEE_NO, PROJECT_ID, null, "2026-02-01", "2026-03-31"),
                    membership(EMPLOYEE_NO, null, PROJECT_CODE, "2026-06-01", null),
                    membership("E-OTHER", PROJECT_ID, PROJECT_CODE, "2026-01-01", null),
                    membership(EMPLOYEE_NO, "P-OTHER", PROJECT_CODE,
                            "2026-01-01", null)
            )));
        });

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.READY);
        assertThat(result.context().membershipAvailable()).isTrue();
        assertThat(result.context().membershipPeriods()).containsExactly(
                new ProjectPeriodContextService.MembershipPeriod(
                        LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 31)
                ),
                new ProjectPeriodContextService.MembershipPeriod(
                        LocalDate.of(2026, 6, 1), null
                )
        );
        assertThat(result.safeMessage()).isNull();

        ArgumentCaptor<DatasetExecutionRequest> requests =
                ArgumentCaptor.forClass(DatasetExecutionRequest.class);
        verify(executionService, times(2)).execute(requests.capture());
        DatasetExecutionRequest membershipRequest = requests.getAllValues().get(1);
        assertThat(membershipRequest.authorization()).isEqualTo(AUTHORIZATION);
        assertThat(membershipRequest.secureContext()).containsEntry("tenant", "t1");
        assertThat(membershipRequest.subjectType()).isEqualTo(BusinessSubjectType.PROJECT);
        assertThat(membershipRequest.subjectId()).isEqualTo(PROJECT_ID);
        assertThat(membershipRequest.canonicalInput())
                .containsEntry("projectId", PROJECT_ID)
                .containsEntry("projectCode", PROJECT_CODE)
                .containsEntry("employeeNo", EMPLOYEE_NO)
                .containsEntry("projectStartDate", "2026-01-01")
                .containsEntry("projectEndDate", "2026-12-31")
                .doesNotContainEntry("employeeNo", "client-employee")
                .doesNotContainEntry("projectId", "client-project");
    }

    @Test
    void malformedMatchingMembershipFactFailsClosedToDirectOnly() {
        prepareBothDatasets();
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            if ("base".equals(request.datasetCode())) {
                return result(request, DatasetExecutionStatus.SUCCESS,
                        projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                                "2026-01-01", "2026-12-31"));
            }
            return result(request, DatasetExecutionStatus.SUCCESS, membershipFacts(List.of(
                    membership(EMPLOYEE_NO, PROJECT_ID, PROJECT_CODE,
                            "invalid", null)
            )));
        });

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertDirectOnlyFailure(result);
    }

    @Test
    void unavailableMembershipExecutionKeepsDirectAssociationWithSafeNotice() {
        for (DatasetExecutionStatus status : List.of(
                DatasetExecutionStatus.DENIED,
                DatasetExecutionStatus.FAILED,
                DatasetExecutionStatus.TIMEOUT
        )) {
            prepareBothDatasets();
            when(executionService.execute(any())).thenAnswer(invocation -> {
                DatasetExecutionRequest request = invocation.getArgument(0);
                return "base".equals(request.datasetCode())
                        ? result(request, DatasetExecutionStatus.SUCCESS,
                        projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                                "2026-01-01", "2026-12-31"))
                        : result(request, status, Map.of());
            });

            assertDirectOnlyFailure(service.resolve(command()));
            reset(datasetService, executionService);
        }

        prepareBothDatasets();
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return "base".equals(request.datasetCode())
                    ? result(request, DatasetExecutionStatus.SUCCESS,
                    projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                            "2026-01-01", "2026-12-31"))
                    : null;
        });
        assertDirectOnlyFailure(service.resolve(command()));

        reset(datasetService, executionService);
        prepareBothDatasets();
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            if ("base".equals(request.datasetCode())) {
                return result(request, DatasetExecutionStatus.SUCCESS,
                        projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                                "2026-01-01", "2026-12-31"));
            }
            throw new IllegalStateException("raw member service error");
        });
        assertDirectOnlyFailure(service.resolve(command()));
    }

    @Test
    void emptyMembershipResultMeansAvailableWithoutMembershipPeriods() {
        prepareBothDatasets();
        when(executionService.execute(any())).thenAnswer(invocation -> {
            DatasetExecutionRequest request = invocation.getArgument(0);
            return "base".equals(request.datasetCode())
                    ? result(request, DatasetExecutionStatus.SUCCESS,
                    projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                            "2026-01-01", "2026-12-31"))
                    : result(request, DatasetExecutionStatus.EMPTY, Map.of());
        });

        ProjectPeriodContextService.Result result = service.resolve(command());

        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.READY);
        assertThat(result.context().membershipAvailable()).isTrue();
        assertThat(result.context().membershipPeriods()).isEmpty();
        assertThat(result.safeMessage()).isNull();
    }

    @Test
    void projectBaseExecutionStatusesMapToSafeTerminalResults() {
        when(datasetService.list()).thenReturn(List.of(dataset("base", "PROJECT_BASE", true)));
        when(executionService.execute(any())).thenAnswer(invocation ->
                result(invocation.getArgument(0), DatasetExecutionStatus.DENIED, Map.of())
        );
        assertThat(service.resolve(command()).status())
                .isEqualTo(ProjectPeriodContextService.Status.DENIED);

        reset(executionService);
        for (DatasetExecutionStatus status : List.of(
                DatasetExecutionStatus.FAILED,
                DatasetExecutionStatus.TIMEOUT,
                DatasetExecutionStatus.PENDING,
                DatasetExecutionStatus.RUNNING
        )) {
            when(executionService.execute(any())).thenAnswer(invocation ->
                    result(invocation.getArgument(0), status, Map.of())
            );
            assertThat(service.resolve(command()).status())
                    .isEqualTo(ProjectPeriodContextService.Status.FAILED);
            reset(executionService);
        }
    }

    @Test
    void nullOrExceptionalProjectBaseExecutionFailsClosed() {
        when(datasetService.list()).thenReturn(List.of(dataset("base", "PROJECT_BASE", true)));
        when(executionService.execute(any())).thenReturn(null);
        assertThat(service.resolve(command()).status())
                .isEqualTo(ProjectPeriodContextService.Status.FAILED);

        reset(executionService);
        doThrow(new IllegalStateException("raw source failure"))
                .when(executionService).execute(any());
        ProjectPeriodContextService.Result failed = service.resolve(command());
        assertThat(failed.status()).isEqualTo(ProjectPeriodContextService.Status.FAILED);
        assertThat(failed.safeMessage()).doesNotContain("raw source failure");
    }

    @Test
    void commandDeepCopiesSecureContextAndToStringHidesSensitiveValues() {
        List<Object> roles = new ArrayList<>(List.of("secret-role"));
        Map<String, Object> secureContext = new LinkedHashMap<>();
        secureContext.put("secret-key", roles);
        ProjectPeriodContextService.Command command = new ProjectPeriodContextService.Command(
                "run-secret", "user-secret", "session-secret", "Bearer token-secret",
                secureContext, "person-token-secret", "project-token-secret", 2026
        );

        roles.add("mutated");
        secureContext.put("late-secret", "late-value");

        assertThat(command.secureContext()).containsOnlyKeys("secret-key");
        assertThat(command.secureContext().get("secret-key")).isEqualTo(List.of("secret-role"));
        assertThatThrownBy(() -> command.secureContext().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(command.toString()).doesNotContain(
                "run-secret", "user-secret", "session-secret", "token-secret",
                "person-token-secret", "project-token-secret", "secret-key", "secret-role"
        );
    }

    private void prepareAuthorizedPersonAndProject() {
        when(tokenService.resolve(PERSON_TOKEN, "user-1", "session-1", BusinessSubjectType.PERSON))
                .thenReturn(Optional.of(EMPLOYEE_NO));
        when(tokenService.resolve(PROJECT_TOKEN, "user-1", "session-1", BusinessSubjectType.PROJECT))
                .thenReturn(Optional.of(PROJECT_ID));
        when(personDirectoryService.search(any())).thenReturn(uniquePage(
                candidate(BusinessSubjectType.PERSON, EMPLOYEE_NO, null)
        ));
        when(projectDirectoryService.search(any())).thenReturn(uniquePage(
                candidate(BusinessSubjectType.PROJECT, PROJECT_ID, PROJECT_CODE)
        ));
    }

    private void prepareBothDatasets() {
        when(datasetService.list()).thenReturn(List.of(
                dataset("base", "PROJECT_BASE", true),
                dataset("member", "PROJECT_MEMBER", true)
        ));
    }

    private void assertDirectoryQuery(
            SubjectDirectoryQuery query,
            BusinessSubjectType type,
            String selectedSubjectId) {
        assertThat(query.agentRunId()).isEqualTo("run-1");
        assertThat(query.userId()).isEqualTo("user-1");
        assertThat(query.sessionId()).isEqualTo("session-1");
        assertThat(query.authorization()).isEqualTo(AUTHORIZATION);
        assertThat(query.secureContext()).containsEntry("tenant", "t1");
        assertThat(query.subjectType()).isEqualTo(type);
        assertThat(query.searchMode().name()).isEqualTo("SELECTED_SUBJECT");
        assertThat(query.selectedSubjectId()).isEqualTo(selectedSubjectId);
    }

    private void assertDirectOnlyFailure(ProjectPeriodContextService.Result result) {
        assertThat(result.status()).isEqualTo(ProjectPeriodContextService.Status.READY);
        assertThat(result.context()).isNotNull();
        assertThat(result.context().membershipAvailable()).isFalse();
        assertThat(result.context().membershipPeriods()).isEmpty();
        assertThat(result.safeMessage()).isEqualTo(
                "项目成员数据暂时不可用，仅能判断项目直接关联记录"
        );
    }

    private ProjectPeriodContextService.Command command() {
        return new ProjectPeriodContextService.Command(
                "run-1",
                "user-1",
                "session-1",
                AUTHORIZATION,
                Map.of(
                        "tenant", "t1",
                        "projectId", "client-project",
                        "projectCode", "CLIENT-CODE",
                        "employeeNo", "client-employee"
                ),
                PERSON_TOKEN,
                PROJECT_TOKEN,
                2026
        );
    }

    private SubjectDirectoryPage uniquePage(AuthorizedSubjectCandidate candidate) {
        return new SubjectDirectoryPage(true, List.of(candidate), 1, 1, 1, false);
    }

    private AuthorizedSubjectCandidate candidate(
            BusinessSubjectType type,
            String rawId,
            String projectCode) {
        return new AuthorizedSubjectCandidate(
                type, rawId, "安全展示名", null, null, projectCode,
                type == BusinessSubjectType.PROJECT ? "工程项目" : null
        );
    }

    private ReportDataset dataset(String code, String domain, boolean enabled) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode(code);
        dataset.setDomainCode(domain);
        dataset.setEnabled(enabled);
        return dataset;
    }

    private Map<String, Object> projectBaseFacts(
            String projectId,
            String projectCode,
            String start,
            String end) {
        return Map.of("calculation", Map.of(
                ProjectPeriodContextService.PROJECT_BASE_FACT,
                Map.of(
                        "projectId", projectId,
                        "projectCode", projectCode,
                        "projectStartDate", start,
                        "projectEndDate", end
                )
        ));
    }

    private Map<String, Object> projectBase(String start, String end) {
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("projectId", PROJECT_ID);
        fact.put("projectStartDate", start);
        fact.put("projectEndDate", end);
        return fact;
    }

    private Map<String, Object> projectBaseIdentity(String projectId, String projectCode) {
        Map<String, Object> fact = new LinkedHashMap<>();
        if (projectId != null) {
            fact.put("projectId", projectId);
        }
        if (projectCode != null) {
            fact.put("projectCode", projectCode);
        }
        fact.put("projectStartDate", "2026-01-01");
        fact.put("projectEndDate", "2026-12-31");
        return fact;
    }

    private void prepareBaseResult(Map<String, Object> base) {
        when(datasetService.list()).thenReturn(List.of(dataset("base", "PROJECT_BASE", true)));
        when(executionService.execute(any())).thenAnswer(invocation ->
                result(invocation.getArgument(0), DatasetExecutionStatus.SUCCESS,
                        Map.of("calculation", Map.of(
                                ProjectPeriodContextService.PROJECT_BASE_FACT, base
                        )))
        );
    }

    private Map<String, Object> membershipFacts(List<Map<String, Object>> records) {
        return Map.of("calculation", Map.of(
                ProjectPeriodContextService.PROJECT_MEMBER_FACT, records
        ));
    }

    private Map<String, Object> membership(
            String employeeNo,
            String projectId,
            String projectCode,
            String start,
            String end) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("employeeNo", employeeNo);
        if (projectId != null) {
            record.put("projectId", projectId);
        }
        if (projectCode != null) {
            record.put("projectCode", projectCode);
        }
        record.put("rosterStart", start);
        if (end != null) {
            record.put("rosterEnd", end);
        }
        return record;
    }

    private DatasetExecutionResult result(
            DatasetExecutionRequest request,
            DatasetExecutionStatus status,
            Map<String, Object> safeFacts) {
        return new DatasetExecutionResult(
                new DatasetExecutionSource(
                        request.userId(), request.sessionId(), request.subjectType(),
                        request.subjectId(), request.datasetCode(), canonicalInputHash(request),
                        "configured-read-workflow", 1L, "b".repeat(64), "c".repeat(64)
                ),
                status,
                status == DatasetExecutionStatus.SUCCESS || status == DatasetExecutionStatus.EMPTY,
                safeFacts,
                "workflow-run-1",
                null,
                null,
                "安全提示",
                "d".repeat(64)
        );
    }

    private DatasetExecutionResult tamperedSourceResult(
            DatasetExecutionRequest request,
            String tampering) {
        DatasetExecutionSource source = new DatasetExecutionSource(
                "userId".equals(tampering) ? "other-user" : request.userId(),
                "sessionId".equals(tampering) ? "other-session" : request.sessionId(),
                "subjectType".equals(tampering)
                        ? BusinessSubjectType.PERSON
                        : request.subjectType(),
                "subjectId".equals(tampering) ? "P-OTHER" : request.subjectId(),
                "datasetCode".equals(tampering) ? "other-dataset" : request.datasetCode(),
                "canonicalInputHash".equals(tampering)
                        ? "f".repeat(64)
                        : canonicalInputHash(request),
                "configured-read-workflow", 1L, "b".repeat(64), "c".repeat(64)
        );
        return new DatasetExecutionResult(
                source,
                DatasetExecutionStatus.SUCCESS,
                true,
                projectBaseFacts(PROJECT_ID, PROJECT_CODE,
                        "2026-01-01", "2026-12-31"),
                "workflow-run-1", null, null, "安全提示", "d".repeat(64)
        );
    }

    private String canonicalInputHash(DatasetExecutionRequest request) {
        return ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(request.canonicalInput())
        );
    }
}
