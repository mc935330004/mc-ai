package org.example.ai.agent.business.department;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService.Command;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService.DepartmentQueryStatus;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService.Result;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.person.BoundedPersonFanOutService;
import org.example.ai.agent.business.person.BoundedPersonFanOutService.PersonRequest;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetPlan;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.model.MultiPersonSummary;
import org.example.ai.agent.business.person.model.MultiPersonSummary.Aggregate;
import org.example.ai.agent.business.person.model.MultiPersonSummary.PersonQueryStatus;
import org.example.ai.agent.business.subject.DepartmentDirectoryService;
import org.example.ai.agent.business.subject.DepartmentMemberDirectoryQuery;
import org.example.ai.agent.business.subject.DepartmentMemberDirectoryService;
import org.example.ai.agent.business.subject.SubjectDirectoryQuery;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.config.BusinessAssistantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DepartmentBusinessQueryServiceTest {

    private final DepartmentDirectoryService departments = mock(DepartmentDirectoryService.class);
    private final DepartmentMemberDirectoryService members = mock(DepartmentMemberDirectoryService.class);
    private final BoundedPersonFanOutService fanOut = mock(BoundedPersonFanOutService.class);
    private final SubjectSelectionTokenService tokens = new SubjectSelectionTokenService(10);
    private final BusinessAssistantProperties properties = new BusinessAssistantProperties();
    private DepartmentBusinessQueryService service;
    private String departmentToken;

    @BeforeEach
    void setUp() {
        service = new DepartmentBusinessQueryService(departments, members, tokens, fanOut, properties);
        departmentToken = tokens.issue("raw-department", "user", "session", BusinessSubjectType.DEPARTMENT);
    }

    @Test
    void deniesBeforeMemberLookupWhenDepartmentTokenIsInvalid() {
        Result result = service.query(command("invalid-token", false), () -> false);

        assertDenied(result);
        verifyNoInteractions(departments, members, fanOut);
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked", "mismatch", "wrongType", "ambiguous", "hasNext", "null"})
    void deniesBeforeMemberLookupWhenDepartmentAuthorizationWasRevoked(String scenario) {
        SubjectDirectoryPage page = switch (scenario) {
            case "revoked" -> SubjectDirectoryPage.denied();
            case "mismatch" -> new SubjectDirectoryPage(true, List.of(department("other-department")), 1, 2, 1, false);
            case "wrongType" -> new SubjectDirectoryPage(true, people(0, 1), 1, 2, 1, false);
            case "ambiguous" -> new SubjectDirectoryPage(true, List.of(department("raw-department")), 1, 2, 2, false);
            case "hasNext" -> new SubjectDirectoryPage(true, List.of(department("raw-department")), 1, 2, 1, true);
            default -> null;
        };
        when(departments.search(any())).thenReturn(page);

        assertDenied(service.query(command(departmentToken, false), () -> false));
        verifyNoInteractions(members, fanOut);
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 100, 500})
    void stopsWithoutFanOutWhenAuthorizedPeopleExceedConfiguredLimit(int configuredLimit) {
        properties.setMaxPeople(configuredLimit);
        authorize();
        int effectiveLimit = Math.min(configuredLimit, 100);
        when(members.search(any())).thenReturn(new SubjectDirectoryPage(
                true, people(0, 1), 1, Math.min(50, effectiveLimit), effectiveLimit + 1L, true));

        Result result = service.query(command(departmentToken, false), () -> false);

        assertThat(result.status()).isEqualTo(DepartmentQueryStatus.LIMIT_EXCEEDED);
        assertThat(result.authorizedPeople()).isEqualTo(effectiveLimit + 1L);
        assertThat(result.processedPeople()).isZero();
        assertThat(result.dataComplete()).isFalse();
        assertThat(result.safeMessage()).isEqualTo("当前授权成员超过单次查询上限，请按下级部门或人员范围缩小查询");
        verify(members, times(1)).search(any());
        verifyNoInteractions(fanOut);
    }

    @Test
    void rejectsHugeTotalBeforeIntegerConversionOrFanOut() {
        authorize();
        when(members.search(any())).thenReturn(new SubjectDirectoryPage(true, List.of(), 1, 50, Long.MAX_VALUE, true));
        Result result = service.query(command(departmentToken, false), () -> false);
        assertThat(result.status()).isEqualTo(DepartmentQueryStatus.LIMIT_EXCEEDED);
        assertThat(result.authorizedPeople()).isEqualTo(Long.MAX_VALUE);
        verify(members, times(1)).search(any());
        verifyNoInteractions(fanOut);
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsTwoPagesAndIssuesFreshPersonTokensBeforeFanOut() {
        authorize();
        when(members.search(any())).thenReturn(
                new SubjectDirectoryPage(true, people(0, 50), 1, 50, 100, true),
                new SubjectDirectoryPage(true, people(50, 100), 2, 50, 100, false));
        when(fanOut.query(any(), any())).thenReturn(summary(MultiPersonSummary.ExecutionStatus.COMPLETED,
                new Aggregate(true, 100, 10, BigDecimal.TEN, BigDecimal.ONE),
                IntStream.range(0, 100).mapToObj(i -> new MultiPersonSummary.PersonStatus("姓名" + i + "（E***）", PersonQueryStatus.SUCCESS)).toList()));
        Command command = command(departmentToken, true);

        Result result = service.query(command, () -> false);

        ArgumentCaptor<SubjectDirectoryQuery> departmentQuery = ArgumentCaptor.forClass(SubjectDirectoryQuery.class);
        verify(departments).search(departmentQuery.capture());
        assertThat(departmentQuery.getValue().subjectType()).isEqualTo(BusinessSubjectType.DEPARTMENT);
        assertThat(departmentQuery.getValue().searchMode()).isEqualTo(SubjectSearchMode.SELECTED_SUBJECT);
        assertThat(departmentQuery.getValue().selectedSubjectId()).isEqualTo("raw-department");
        assertThat(departmentQuery.getValue().employeeNo()).isNull();
        assertThat(departmentQuery.getValue().projectCode()).isNull();
        assertThat(departmentQuery.getValue().searchName()).isNull();
        assertThat(departmentQuery.getValue().projectManager()).isNull();
        assertThat(departmentQuery.getValue().projectYear()).isNull();
        assertThat(departmentQuery.getValue().pageNumber()).isOne();
        assertThat(departmentQuery.getValue().pageSize()).isEqualTo(2);
        ArgumentCaptor<DepartmentMemberDirectoryQuery> pageQueries = ArgumentCaptor.forClass(DepartmentMemberDirectoryQuery.class);
        verify(members, times(2)).search(pageQueries.capture());
        assertThat(pageQueries.getAllValues()).extracting(DepartmentMemberDirectoryQuery::pageNumber).containsExactly(1, 2);
        assertThat(pageQueries.getAllValues()).allSatisfy(query -> {
            assertThat(query.departmentSubjectId()).isEqualTo("raw-department");
            assertThat(query.pageSize()).isEqualTo(50);
            assertThat(query.authorization()).isEqualTo(command.authorization());
            assertThat(query.secureContext()).isEqualTo(command.secureContext());
        });
        ArgumentCaptor<List<PersonRequest>> requests = ArgumentCaptor.forClass(List.class);
        verify(fanOut).query(requests.capture(), any());
        assertThat(requests.getValue()).hasSize(100);
        Set<String> freshTokens = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            PersonRequest request = requests.getValue().get(i);
            assertThat(request.displayLabel()).isEqualTo("姓名" + i + "（E***）");
            assertThat(request.allowAnomalyDisclosure()).isTrue();
            assertThat(request.command().plans()).isSameAs(command.plans());
            assertThat(request.command().agentRunId()).isEqualTo(command.agentRunId());
            assertThat(request.command().userId()).isEqualTo(command.userId());
            assertThat(request.command().sessionId()).isEqualTo(command.sessionId());
            assertThat(request.command().authorization()).isEqualTo(command.authorization());
            assertThat(request.command().secureContext()).isEqualTo(command.secureContext());
            assertThat(request.command().refreshRequested()).isTrue();
            assertThat(tokens.resolve(request.command().selectionToken(), "user", "session", BusinessSubjectType.PERSON)).contains("raw-person-" + i);
            assertThat(freshTokens.add(request.command().selectionToken())).isTrue();
            assertThat(request.command().selectionToken()).isNotEqualTo(departmentToken);
        }
        assertThat(result.status()).isEqualTo(DepartmentQueryStatus.COMPLETED);
        assertThat(result.dataComplete()).isTrue();
        assertThat(result.authorizedPeople()).isEqualTo(100);
        assertThat(result.loadedPeople()).isEqualTo(100);
        assertThat(result.processedPeople()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"totalDrift", "duplicate", "empty", "pageNumber", "pageSize", "hasNext", "wrongType", "denied", "null", "exception"})
    void failsClosedForChangedTotalDuplicateMemberOrPrematureEmptyPage(String scenario) {
        authorize();
        SubjectDirectoryPage second = switch (scenario) {
            case "totalDrift" -> new SubjectDirectoryPage(true, people(50, 51), 2, 50, 52, false);
            case "duplicate" -> new SubjectDirectoryPage(true, people(0, 1), 2, 50, 51, false);
            case "empty" -> new SubjectDirectoryPage(true, List.of(), 2, 50, 51, false);
            case "pageNumber" -> new SubjectDirectoryPage(true, people(50, 51), 3, 50, 51, false);
            case "pageSize" -> new SubjectDirectoryPage(true, people(50, 51), 2, 20, 51, false);
            case "hasNext" -> new SubjectDirectoryPage(true, people(50, 51), 2, 50, 51, true);
            case "wrongType" -> new SubjectDirectoryPage(true, List.of(department("wrong-type")), 2, 50, 51, false);
            case "denied" -> SubjectDirectoryPage.denied();
            default -> null;
        };
        when(members.search(any())).thenAnswer(invocation -> {
            DepartmentMemberDirectoryQuery query = invocation.getArgument(0);
            if (query.pageNumber() == 1) {
                return new SubjectDirectoryPage(true, people(0, 50), 1, 50, 51, true);
            }
            if (scenario.equals("exception")) {
                throw new IllegalStateException("private-source-response raw-person-50");
            }
            return second;
        });

        assertDenied(service.query(command(departmentToken, false), () -> false));
        verifyNoInteractions(fanOut);
    }

    @ParameterizedTest
    @ValueSource(strings = {"emptyWithNext", "shortPage", "wrongPage", "denied", "exception"})
    void rejectsInvalidFirstPageBeforeLoadingFurtherMembers(String scenario) {
        authorize();
        when(members.search(any())).thenAnswer(invocation -> switch (scenario) {
            case "emptyWithNext" -> new SubjectDirectoryPage(true, List.of(), 1, 50, 51, true);
            case "shortPage" -> new SubjectDirectoryPage(true, people(0, 49), 1, 50, 51, true);
            case "wrongPage" -> new SubjectDirectoryPage(true, people(0, 1), 2, 50, 1, false);
            case "denied" -> SubjectDirectoryPage.denied();
            default -> throw new IllegalStateException("private-source-response");
        });
        assertDenied(service.query(command(departmentToken, false), () -> false));
        verify(members, times(1)).search(any());
        verifyNoInteractions(fanOut);
    }

    @Test
    void returnsCompletedZeroSummaryForAnEmptyAuthorizedDepartment() {
        authorize();
        when(members.search(any())).thenReturn(SubjectDirectoryPage.empty(1, 50));

        Result result = service.query(command(departmentToken, false), () -> false);

        assertThat(result.status()).isEqualTo(DepartmentQueryStatus.COMPLETED);
        assertThat(result.dataComplete()).isTrue();
        assertThat(result.authorizedPeople()).isZero();
        assertThat(result.loadedPeople()).isZero();
        assertThat(result.processedPeople()).isZero();
        assertThat(result.aggregate()).isEqualTo(new Aggregate(true, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(result.statusCounts()).hasSize(PersonQueryStatus.values().length);
        assertThat(result.statusCounts().values()).containsOnly(0);
        verifyNoInteractions(fanOut);
    }

    @Test
    void countsEveryPersonTerminalStatusWithoutExposingPeopleList() throws Exception {
        authorizeMembers(5);
        List<MultiPersonSummary.PersonStatus> statuses = Arrays.stream(PersonQueryStatus.values())
                .map(status -> new MultiPersonSummary.PersonStatus("private-full-member-list", status)).toList();
        when(fanOut.query(any(), any())).thenReturn(summary(MultiPersonSummary.ExecutionStatus.PARTIAL,
                new Aggregate(false, 1, null, null, null), statuses));

        Result result = service.query(command(departmentToken, false), () -> false);

        assertThat(result.statusCounts()).hasSize(5);
        assertThat(result.statusCounts().values()).containsOnly(1);
        assertThat(result.processedPeople()).isEqualTo(5);
        assertThatThrownBy(() -> result.statusCounts().put(PersonQueryStatus.SUCCESS, 99)).isInstanceOf(UnsupportedOperationException.class);
        String json = new ObjectMapper().writeValueAsString(result);
        assertThat(json).doesNotContain("\"people\"", "private-full-member-list", "raw-person", "raw-department", departmentToken, "facts");
    }

    @Test
    void keepsFormalTotalsEmptyWhenFanOutIsPartial() {
        authorizeMembers(2);
        Aggregate incomplete = new Aggregate(false, 1, null, null, null);
        when(fanOut.query(any(), any())).thenReturn(summary(MultiPersonSummary.ExecutionStatus.PARTIAL, incomplete,
                List.of(new MultiPersonSummary.PersonStatus("姓名0（E***）", PersonQueryStatus.SUCCESS),
                        new MultiPersonSummary.PersonStatus("姓名1（E***）", PersonQueryStatus.TIMEOUT))));

        Result result = service.query(command(departmentToken, false), () -> false);

        assertThat(result.status()).isEqualTo(DepartmentQueryStatus.PARTIAL);
        assertThat(result.dataComplete()).isFalse();
        assertThat(result.aggregate()).isSameAs(incomplete);
        assertThat(result.aggregate().tripCount()).isNull();
        assertThat(result.aggregate().travelAmount()).isNull();
        assertThat(result.aggregate().reimbursementAmount()).isNull();
    }

    @Test
    void passesCallerCancellationSignalToFanOut() {
        authorizeMembers(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        BooleanSupplier signal = cancelled::get;
        when(fanOut.query(any(), same(signal))).thenAnswer(invocation -> {
            cancelled.set(true);
            BooleanSupplier supplied = invocation.getArgument(1);
            assertThat(supplied).isSameAs(signal);
            assertThat(supplied.getAsBoolean()).isTrue();
            return summary(MultiPersonSummary.ExecutionStatus.CANCELLED, new Aggregate(false, 0, null, null, null),
                    List.of(new MultiPersonSummary.PersonStatus("姓名0（E***）", PersonQueryStatus.CANCELLED)));
        });

        Result result = service.query(command(departmentToken, false), signal);

        assertThat(result.status()).isEqualTo(DepartmentQueryStatus.CANCELLED);
        assertThat(result.dataComplete()).isFalse();
        assertThat(result.statusCounts()).containsEntry(PersonQueryStatus.CANCELLED, 1);
        verify(fanOut).query(any(), same(signal));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disclosesOnlyRequestedAnomalyPeople(boolean requested) {
        authorizeMembers(1);
        List<MultiPersonSummary.AnomalyPerson> anomalies = List.of(new MultiPersonSummary.AnomalyPerson("姓名0（E***）", List.of("MISSING_PUNCH")));
        when(fanOut.query(any(), any())).thenReturn(new MultiPersonSummary(MultiPersonSummary.ExecutionStatus.COMPLETED,
                new Aggregate(true, 1, 0, BigDecimal.ZERO, BigDecimal.ZERO),
                List.of(new MultiPersonSummary.PersonStatus("姓名0（E***）", PersonQueryStatus.SUCCESS)), anomalies, null));
        Result result = service.query(command(departmentToken, requested), () -> false);
        assertThat(result.anomalyPeople()).isEqualTo(requested ? anomalies : List.of());
        assertThatThrownBy(() -> result.anomalyPeople().add(anomalies.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resultStringContainsOnlySafeSummaryWithoutAnomalyPeopleOrAmounts() {
        Result result = new Result(DepartmentQueryStatus.COMPLETED, true, 1, 1, 1,
                new Aggregate(true, 1, 7, new BigDecimal("12345.67"), new BigDecimal("98765.43")),
                Map.of(PersonQueryStatus.SUCCESS, 1),
                List.of(new MultiPersonSummary.AnomalyPerson("张三（E***01）", List.of("MISSING_PUNCH"))),
                "private-safe-message");

        assertThat(result.toString())
                .doesNotContain("张三", "E***01", "MISSING_PUNCH", "12345.67", "98765.43", "private-safe-message")
                .isEqualTo("Result[status=COMPLETED, dataComplete=true, authorizedPeople=1, loadedPeople=1, "
                        + "processedPeople=1, statusCount=5, anomalyCount=1]");
    }

    @Test
    void freezesCommandInputsAndRedactsItsStringRepresentation() {
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("role", "private-role"));
        Map<String, Object> context = new LinkedHashMap<>(Map.of("nested", nested));
        List<DatasetPlan> mutablePlans = new ArrayList<>(plans());
        Command command = new Command("private-run", "private-user", "private-session", "private-auth", context,
                "private-token", true, false, mutablePlans);
        nested.put("role", "changed");
        mutablePlans.clear();
        assertThat(command.secureContext()).containsEntry("nested", Map.of("role", "private-role"));
        assertThat(command.plans()).hasSize(6);
        assertThatThrownBy(() -> command.secureContext().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> command.plans().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(command.toString()).doesNotContain("private-", "PERSON_");
    }

    @Test
    void rejectsInvalidCommandsBeforeAnyDirectoryLookup() {
        assertThatThrownBy(() -> service.query(null, () -> false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(command(departmentToken, false), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new Command("run", "user", "session", "auth", Map.of(), departmentToken, false, false, List.of()), () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new Command("run", "u".repeat(257), "session", "auth", Map.of(), departmentToken, false, false, plans()), () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new Command("run", "user", "session", "auth", Map.of("large", "x".repeat(17000)), departmentToken, false, false, plans()), () -> false))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(departments, members, fanOut);
    }

    private void authorize() {
        when(departments.search(any())).thenReturn(new SubjectDirectoryPage(true, List.of(department("raw-department")), 1, 2, 1, false));
    }

    private void authorizeMembers(int count) {
        authorize();
        when(members.search(any())).thenReturn(new SubjectDirectoryPage(true, people(0, count), 1, 50, count, false));
    }

    private AuthorizedSubjectCandidate department(String id) {
        return new AuthorizedSubjectCandidate(BusinessSubjectType.DEPARTMENT, id, "部门", null, null, null, null);
    }

    private List<AuthorizedSubjectCandidate> people(int start, int end) {
        return IntStream.range(start, end).mapToObj(i -> new AuthorizedSubjectCandidate(BusinessSubjectType.PERSON,
                "raw-person-" + i, "姓名" + i, "E***", "不应进入展示的部门路径", null, null)).toList();
    }

    private Command command(String token, boolean anomalies) {
        return new Command("run", "user", "session", "Bearer private-auth", Map.of("roles", List.of("HR")), token, true, anomalies, plans());
    }

    private List<DatasetPlan> plans() {
        return Arrays.stream(DatasetType.values()).map(type -> new DatasetPlan(type, "PERSON_" + type.name(),
                Map.of("startDate", "2026-09-01", "endDate", "2026-09-10"), "DAY",
                Set.of("person_" + type.name().toLowerCase(java.util.Locale.ROOT) + "_records"),
                type == DatasetType.TRAVEL || type == DatasetType.PUNCH
                        || type == DatasetType.REIMBURSEMENT)).toList();
    }

    private MultiPersonSummary summary(MultiPersonSummary.ExecutionStatus status, Aggregate aggregate, List<MultiPersonSummary.PersonStatus> people) {
        return new MultiPersonSummary(status, aggregate, people, List.of(), null);
    }

    private void assertDenied(Result result) {
        assertThat(result.status()).isEqualTo(DepartmentQueryStatus.DENIED);
        assertThat(result.dataComplete()).isFalse();
        assertThat(result.processedPeople()).isZero();
        assertThat(result.aggregate().complete()).isFalse();
        assertThat(result.aggregate().tripCount()).isNull();
        assertThat(result.aggregate().travelAmount()).isNull();
        assertThat(result.aggregate().reimbursementAmount()).isNull();
        assertThat(result.safeMessage()).isEqualTo("无法完成部门查询，请确认当前可查询范围和配置");
    }
}
