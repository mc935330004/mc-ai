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
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 独立复核人员和项目权限，并从可配置数据集取得项目期间。
 *
 * 本服务只读取字段策略产出的 calculation 安全事实，不读取工作流原始响应。
 */
@Service
public class ProjectPeriodContextService {

    public static final String PROJECT_BASE_DOMAIN = "PROJECT_BASE";
    public static final String PROJECT_MEMBER_DOMAIN = "PROJECT_MEMBER";
    public static final String PROJECT_BASE_FACT = "project_base";
    public static final String PROJECT_MEMBER_FACT = "project_membership_records";

    private static final String SAFE_DENIED = "无法确认主体权限，暂时不能按项目期间查询";
    private static final String SAFE_FAILED = "项目期间数据暂时不可用，请稍后重试";
    private static final String SAFE_BASE_NOT_CONFIGURED =
            "项目基础数据源尚未配置，暂时无法取得项目期间";
    private static final String SAFE_PERIOD_UNAVAILABLE =
            "项目基础数据未提供完整起止日期，请补充日期范围或完善字段映射";
    private static final String SAFE_MEMBER_NOT_CONFIGURED =
            "项目成员数据源尚未配置，仅能判断项目直接关联记录";
    private static final String SAFE_MEMBER_UNAVAILABLE =
            "项目成员数据暂时不可用，仅能判断项目直接关联记录";

    private final SubjectSelectionTokenService tokenService;
    private final AuthorizedPersonDirectoryService personDirectoryService;
    private final ProjectDirectoryService projectDirectoryService;
    private final ReportDatasetService datasetService;
    private final ReportDatasetExecutionService executionService;
    private final DatasetExecutionProofVerifier proofVerifier;

    public ProjectPeriodContextService(
            SubjectSelectionTokenService tokenService,
            AuthorizedPersonDirectoryService personDirectoryService,
            ProjectDirectoryService projectDirectoryService,
            ReportDatasetService datasetService,
            ReportDatasetExecutionService executionService,
            DatasetExecutionProofVerifier proofVerifier) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService不能为空");
        this.personDirectoryService = Objects.requireNonNull(
                personDirectoryService, "personDirectoryService不能为空"
        );
        this.projectDirectoryService = Objects.requireNonNull(
                projectDirectoryService, "projectDirectoryService不能为空"
        );
        this.datasetService = Objects.requireNonNull(datasetService, "datasetService不能为空");
        this.executionService = Objects.requireNonNull(
                executionService, "executionService不能为空"
        );
        this.proofVerifier = Objects.requireNonNull(proofVerifier, "proofVerifier不能为空");
    }

    /**
     * 按“人员复权、项目复权、项目基础、项目成员”的固定顺序解析上下文。
     */
    public Result resolve(Command command) {
        ValidatedCommand validated = validate(command);

        AuthorizationResult person = authorizePerson(validated);
        if (person.failed()) {
            return failed();
        }
        if (person.candidate() == null) {
            return denied();
        }

        AuthorizationResult project = authorizeProject(validated);
        if (project.failed()) {
            return failed();
        }
        if (project.candidate() == null) {
            return denied();
        }

        DatasetChoice baseChoice = findDataset(PROJECT_BASE_DOMAIN);
        if (baseChoice.failed()) {
            return failed();
        }
        if (baseChoice.dataset() == null) {
            return unavailable(Status.PROJECT_DATASET_NOT_CONFIGURED, SAFE_BASE_NOT_CONFIGURED);
        }

        DatasetExecutionResult baseResult = execute(
                validated,
                project.candidate(),
                baseChoice.dataset(),
                projectBaseInput(project.candidate(), validated.projectYear())
        );
        Result baseFailure = baseFailure(baseResult);
        if (baseFailure != null) {
            return baseFailure;
        }
        Context baseContext = readProjectPeriod(baseResult, project.candidate());
        if (baseContext == null) {
            return unavailable(Status.PERIOD_UNAVAILABLE, SAFE_PERIOD_UNAVAILABLE);
        }

        DatasetChoice memberChoice = findDataset(PROJECT_MEMBER_DOMAIN);
        if (memberChoice.failed()) {
            return failed();
        }
        if (memberChoice.dataset() == null) {
            return new Result(
                    Status.READY,
                    withoutMembership(baseContext),
                    SAFE_MEMBER_NOT_CONFIGURED
            );
        }
        return resolveMembership(
                validated,
                person.candidate(),
                project.candidate(),
                baseContext,
                memberChoice.dataset()
        );
    }

    private ValidatedCommand validate(Command command) {
        if (command == null
                || !StringUtils.hasText(command.agentRunId())
                || !StringUtils.hasText(command.userId())
                || !StringUtils.hasText(command.sessionId())
                || !StringUtils.hasText(command.authorization())
                || !StringUtils.hasText(command.personSelectionToken())
                || !StringUtils.hasText(command.projectSelectionToken())) {
            throw new IllegalArgumentException("项目期间上下文命令不完整");
        }
        if (command.projectYear() != null
                && (command.projectYear() < 1900 || command.projectYear() > 9999)) {
            throw new IllegalArgumentException("项目年度不合法");
        }
        return new ValidatedCommand(
                command.agentRunId().trim(),
                command.userId().trim(),
                command.sessionId().trim(),
                command.authorization(),
                command.secureContext(),
                command.personSelectionToken(),
                command.projectSelectionToken(),
                command.projectYear()
        );
    }

    private AuthorizationResult authorizePerson(ValidatedCommand command) {
        String personId;
        try {
            personId = tokenService.resolve(
                    command.personSelectionToken(),
                    command.userId(),
                    command.sessionId(),
                    BusinessSubjectType.PERSON
            ).orElse(null);
        } catch (RuntimeException exception) {
            return AuthorizationResult.executionFailed();
        }
        if (!StringUtils.hasText(personId)) {
            return AuthorizationResult.denied();
        }
        try {
            SubjectDirectoryPage page = personDirectoryService.search(directoryQuery(
                    command, BusinessSubjectType.PERSON, personId
            ));
            return AuthorizationResult.authorized(uniqueCandidate(
                    page, BusinessSubjectType.PERSON, personId
            ));
        } catch (RuntimeException exception) {
            return AuthorizationResult.executionFailed();
        }
    }

    private AuthorizationResult authorizeProject(ValidatedCommand command) {
        String projectId;
        try {
            projectId = tokenService.resolve(
                    command.projectSelectionToken(),
                    command.userId(),
                    command.sessionId(),
                    BusinessSubjectType.PROJECT
            ).orElse(null);
        } catch (RuntimeException exception) {
            return AuthorizationResult.executionFailed();
        }
        if (!StringUtils.hasText(projectId)) {
            return AuthorizationResult.denied();
        }
        try {
            SubjectDirectoryPage page = projectDirectoryService.search(directoryQuery(
                    command, BusinessSubjectType.PROJECT, projectId
            ));
            return AuthorizationResult.authorized(uniqueCandidate(
                    page, BusinessSubjectType.PROJECT, projectId
            ));
        } catch (RuntimeException exception) {
            return AuthorizationResult.executionFailed();
        }
    }

    private SubjectDirectoryQuery directoryQuery(
            ValidatedCommand command,
            BusinessSubjectType type,
            String selectedSubjectId) {
        return new SubjectDirectoryQuery(
                command.agentRunId(),
                command.userId(),
                command.sessionId(),
                command.authorization(),
                command.secureContext(),
                type,
                SubjectSearchMode.SELECTED_SUBJECT,
                selectedSubjectId,
                null,
                null,
                null,
                type == BusinessSubjectType.PROJECT ? command.projectYear() : null,
                type == BusinessSubjectType.PERSON ? selectedSubjectId : null,
                1,
                2
        );
    }

    private AuthorizedSubjectCandidate uniqueCandidate(
            SubjectDirectoryPage page,
            BusinessSubjectType expectedType,
            String expectedId) {
        boolean unique = page != null
                && page.accessible()
                && page.candidates().size() == 1
                && page.totalCount() == 1
                && !page.hasNext();
        if (!unique) {
            return null;
        }
        AuthorizedSubjectCandidate candidate = page.candidates().get(0);
        if (candidate == null
                || candidate.type() != expectedType
                || !Objects.equals(candidate.rawSubjectId(), expectedId)) {
            return null;
        }
        return candidate;
    }

    private DatasetChoice findDataset(String domainCode) {
        List<ReportDataset> configurations;
        try {
            configurations = datasetService.list();
        } catch (RuntimeException exception) {
            return DatasetChoice.executionFailed();
        }
        ReportDataset matched = null;
        if (configurations == null) {
            return DatasetChoice.missing();
        }
        for (ReportDataset dataset : configurations) {
            if (!matchesDomain(dataset, domainCode)) {
                continue;
            }
            if (matched != null || !StringUtils.hasText(dataset.getDatasetCode())) {
                return DatasetChoice.executionFailed();
            }
            matched = dataset;
        }
        return new DatasetChoice(matched, false);
    }

    private boolean matchesDomain(ReportDataset dataset, String domainCode) {
        return dataset != null
                && Boolean.TRUE.equals(dataset.getEnabled())
                && StringUtils.hasText(dataset.getDomainCode())
                && domainCode.equals(dataset.getDomainCode().trim().toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> projectBaseInput(
            AuthorizedSubjectCandidate project,
            Integer projectYear) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", project.rawSubjectId());
        if (StringUtils.hasText(project.projectCode())) {
            input.put("projectCode", project.projectCode());
        }
        if (projectYear != null) {
            input.put("projectYear", projectYear);
        }
        return input;
    }

    private DatasetExecutionResult execute(
            ValidatedCommand command,
            AuthorizedSubjectCandidate project,
            ReportDataset dataset,
            Map<String, Object> canonicalInput) {
        DatasetExecutionRequest request = new DatasetExecutionRequest(
                command.agentRunId(),
                command.userId(),
                command.sessionId(),
                command.authorization(),
                command.secureContext(),
                dataset.getDatasetCode(),
                BusinessSubjectType.PROJECT,
                project.rawSubjectId(),
                canonicalInput
        );
        try {
            DatasetExecutionResult result = executionService.execute(request);
            return trustedResult(request, result) ? result : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** 验证结果由受控执行链产生，并且来源与本次请求完全一致。 */
    private boolean trustedResult(
            DatasetExecutionRequest request,
            DatasetExecutionResult result) {
        if (result == null || !proofVerifier.verify(result)) {
            return false;
        }
        DatasetExecutionSource source = result.source();
        String inputHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(request.canonicalInput())
        );
        return source != null
                && Objects.equals(source.userId(), request.userId())
                && Objects.equals(source.sessionId(), request.sessionId())
                && source.subjectType() == BusinessSubjectType.PROJECT
                && Objects.equals(source.subjectId(), request.subjectId())
                && Objects.equals(source.datasetCode(), request.datasetCode())
                && Objects.equals(source.canonicalInputHash(), inputHash);
    }

    private Result baseFailure(DatasetExecutionResult result) {
        if (result == null || result.status() == null) {
            return failed();
        }
        if (result.status() == DatasetExecutionStatus.DENIED) {
            return denied();
        }
        if (result.status() != DatasetExecutionStatus.SUCCESS
                && result.status() != DatasetExecutionStatus.EMPTY) {
            return failed();
        }
        return null;
    }

    private Context readProjectPeriod(
            DatasetExecutionResult result,
            AuthorizedSubjectCandidate project) {
        Map<?, ?> fact = factMap(result, PROJECT_BASE_FACT);
        if (fact == null || !hasProjectIdentity(fact) || !matchesProject(fact, project)) {
            return null;
        }
        LocalDate start = date(fact.get("projectStartDate"));
        LocalDate end = date(fact.get("projectEndDate"));
        if (start == null || end == null || start.isAfter(end)) {
            return null;
        }
        // 项目标识始终采用刚完成复权的目录结果，不信任事实中的同名字段。
        return new Context(
                project.projectCode(),
                project.rawSubjectId(),
                start,
                end,
                List.of(),
                false
        );
    }

    private Result resolveMembership(
            ValidatedCommand command,
            AuthorizedSubjectCandidate person,
            AuthorizedSubjectCandidate project,
            Context baseContext,
            ReportDataset memberDataset) {
        DatasetExecutionResult memberResult = execute(
                command,
                project,
                memberDataset,
                membershipInput(command, person, project, baseContext)
        );
        if (memberResult == null || memberResult.status() == null) {
            return directOnly(baseContext);
        }
        if (memberResult.status() == DatasetExecutionStatus.EMPTY) {
            return readyWithMembership(baseContext, List.of());
        }
        if (memberResult.status() != DatasetExecutionStatus.SUCCESS) {
            return directOnly(baseContext);
        }
        List<MembershipPeriod> periods = readMembershipPeriods(
                memberResult,
                person.rawSubjectId(),
                project
        );
        return periods == null
                ? directOnly(baseContext)
                : readyWithMembership(baseContext, periods);
    }

    private Map<String, Object> membershipInput(
            ValidatedCommand command,
            AuthorizedSubjectCandidate person,
            AuthorizedSubjectCandidate project,
            Context context) {
        Map<String, Object> input = projectBaseInput(project, command.projectYear());
        input.put("employeeNo", person.rawSubjectId());
        input.put("projectStartDate", context.periodStart().toString());
        input.put("projectEndDate", context.periodEnd().toString());
        return input;
    }

    private List<MembershipPeriod> readMembershipPeriods(
            DatasetExecutionResult result,
            String employeeNo,
            AuthorizedSubjectCandidate project) {
        Object fact = calculation(result).get(PROJECT_MEMBER_FACT);
        if (!(fact instanceof List<?> records)) {
            return null;
        }
        List<MembershipPeriod> periods = new ArrayList<>();
        try {
            for (Object item : records) {
                if (!(item instanceof Map<?, ?> record)) {
                    return null;
                }
                String recordEmployee = string(record.get("employeeNo"));
                if (recordEmployee == null) {
                    return null;
                }
                if (!employeeNo.equals(recordEmployee)) {
                    continue;
                }
                if (!hasProjectIdentity(record)) {
                    return null;
                }
                if (!matchesProject(record, project)) {
                    continue;
                }
                LocalDate start = requiredDate(record.get("rosterStart"));
                LocalDate end = optionalDate(record.get("rosterEnd"));
                periods.add(new MembershipPeriod(start, end));
            }
            return List.copyOf(periods);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean matchesProject(
            Map<?, ?> record,
            AuthorizedSubjectCandidate project) {
        String recordId = string(record.get("projectId"));
        String recordCode = string(record.get("projectCode"));
        if (recordId != null && !project.rawSubjectId().equals(recordId)) {
            return false;
        }
        if (recordCode != null
                && (!StringUtils.hasText(project.projectCode())
                || !project.projectCode().equalsIgnoreCase(recordCode))) {
            return false;
        }
        return true;
    }

    private boolean hasProjectIdentity(Map<?, ?> fact) {
        return text(fact.get("projectId")) || text(fact.get("projectCode"));
    }

    private Map<?, ?> factMap(DatasetExecutionResult result, String factCode) {
        Object fact = calculation(result).get(factCode);
        return fact instanceof Map<?, ?> map ? map : null;
    }

    private Map<?, ?> calculation(DatasetExecutionResult result) {
        Object calculation = result.safeFacts().get("calculation");
        return calculation instanceof Map<?, ?> map ? map : Map.of();
    }

    private LocalDate requiredDate(Object value) {
        LocalDate parsed = date(value);
        if (parsed == null) {
            throw new IllegalArgumentException("项目成员开始日期不合法");
        }
        return parsed;
    }

    private LocalDate optionalDate(Object value) {
        if (value == null) {
            return null;
        }
        LocalDate parsed = date(value);
        if (parsed == null) {
            throw new IllegalArgumentException("项目成员结束日期不合法");
        }
        return parsed;
    }

    private LocalDate date(Object value) {
        String date = string(value);
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private String string(Object value) {
        return value instanceof String text && StringUtils.hasText(text)
                ? text.trim()
                : null;
    }

    private boolean text(Object value) {
        return string(value) != null;
    }

    private Context withoutMembership(Context context) {
        return new Context(
                context.projectCode(),
                context.projectId(),
                context.periodStart(),
                context.periodEnd(),
                List.of(),
                false
        );
    }

    private Result directOnly(Context context) {
        return new Result(Status.READY, withoutMembership(context), SAFE_MEMBER_UNAVAILABLE);
    }

    private Result readyWithMembership(
            Context context,
            List<MembershipPeriod> periods) {
        return new Result(
                Status.READY,
                new Context(
                        context.projectCode(),
                        context.projectId(),
                        context.periodStart(),
                        context.periodEnd(),
                        periods,
                        true
                ),
                null
        );
    }

    private Result denied() {
        return unavailable(Status.DENIED, SAFE_DENIED);
    }

    private Result failed() {
        return unavailable(Status.FAILED, SAFE_FAILED);
    }

    private Result unavailable(Status status, String safeMessage) {
        return new Result(status, null, safeMessage);
    }

    public enum Status {
        READY,
        PROJECT_DATASET_NOT_CONFIGURED,
        PERIOD_UNAVAILABLE,
        DENIED,
        FAILED
    }

    /**
     * 项目期间解析命令。认证信息与安全上下文只在本次内存调用中传递。
     */
    public record Command(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String personSelectionToken,
            String projectSelectionToken,
            Integer projectYear) {

        @SuppressWarnings("unchecked")
        public Command {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
        }

        /**
         * 只输出是否存在敏感字段，不输出认证、令牌、上下文和主体值。
         */
        @Override
        public String toString() {
            return "Command["
                    + "authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextPresent=" + !secureContext.isEmpty()
                    + ", personSelectionPresent=" + StringUtils.hasText(personSelectionToken)
                    + ", projectSelectionPresent=" + StringUtils.hasText(projectSelectionToken)
                    + ", projectYearPresent=" + (projectYear != null)
                    + ']';
        }
    }

    public record MembershipPeriod(LocalDate start, LocalDate end) {

        public MembershipPeriod {
            if (start == null || end != null && start.isAfter(end)) {
                throw new IllegalArgumentException("项目成员有效期不合法");
            }
        }
    }

    public record Context(
            String projectCode,
            String projectId,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<MembershipPeriod> membershipPeriods,
            boolean membershipAvailable) {

        public Context {
            membershipPeriods = membershipPeriods == null
                    ? List.of()
                    : List.copyOf(membershipPeriods);
        }
    }

    public record Result(Status status, Context context, String safeMessage) {

        public boolean ready() {
            return status == Status.READY && context != null;
        }
    }

    private record ValidatedCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String personSelectionToken,
            String projectSelectionToken,
            Integer projectYear) {
    }

    private record AuthorizationResult(
            AuthorizedSubjectCandidate candidate,
            boolean failed) {

        private static AuthorizationResult authorized(AuthorizedSubjectCandidate candidate) {
            return new AuthorizationResult(candidate, false);
        }

        private static AuthorizationResult denied() {
            return new AuthorizationResult(null, false);
        }

        private static AuthorizationResult executionFailed() {
            return new AuthorizationResult(null, true);
        }
    }

    private record DatasetChoice(ReportDataset dataset, boolean failed) {

        private static DatasetChoice missing() {
            return new DatasetChoice(null, false);
        }

        private static DatasetChoice executionFailed() {
            return new DatasetChoice(null, true);
        }
    }
}
