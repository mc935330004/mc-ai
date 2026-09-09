package org.example.ai.agent.business.department;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.person.BoundedPersonFanOutService;
import org.example.ai.agent.business.person.BoundedPersonFanOutService.PersonRequest;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetPlan;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.model.MultiPersonSummary;
import org.example.ai.agent.business.person.model.MultiPersonSummary.Aggregate;
import org.example.ai.agent.business.person.model.MultiPersonSummary.AnomalyPerson;
import org.example.ai.agent.business.person.model.MultiPersonSummary.PersonQueryStatus;
import org.example.ai.agent.business.subject.DepartmentDirectoryService;
import org.example.ai.agent.business.subject.DepartmentMemberDirectoryQuery;
import org.example.ai.agent.business.subject.DepartmentMemberDirectoryService;
import org.example.ai.agent.business.subject.SubjectDirectoryQuery;
import org.example.ai.agent.business.subject.SubjectRequestLimits;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.config.BusinessAssistantProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 先复核部门权限、拉齐授权成员，再执行有界逐人查询。
 * 目录不完整时不启动人员业务调用；返回值仅包含部门汇总和允许披露的异常人员。
 */
@Service
public class DepartmentBusinessQueryService {

    private static final int HARD_MAX_PEOPLE = 100;
    private static final String DENIED_MESSAGE = "无法完成部门查询，请确认当前可查询范围和配置";
    private static final String LIMIT_MESSAGE = "当前授权成员超过单次查询上限，请按下级部门或人员范围缩小查询";

    private final DepartmentDirectoryService departmentDirectoryService;
    private final DepartmentMemberDirectoryService memberDirectoryService;
    private final SubjectSelectionTokenService selectionTokenService;
    private final BoundedPersonFanOutService fanOutService;
    private final BusinessAssistantProperties properties;

    public DepartmentBusinessQueryService(
            DepartmentDirectoryService departmentDirectoryService,
            DepartmentMemberDirectoryService memberDirectoryService,
            SubjectSelectionTokenService selectionTokenService,
            BoundedPersonFanOutService fanOutService,
            BusinessAssistantProperties properties) {
        this.departmentDirectoryService = Objects.requireNonNull(departmentDirectoryService, "departmentDirectoryService不能为空");
        this.memberDirectoryService = Objects.requireNonNull(memberDirectoryService, "memberDirectoryService不能为空");
        this.selectionTokenService = Objects.requireNonNull(selectionTokenService, "selectionTokenService不能为空");
        this.fanOutService = Objects.requireNonNull(fanOutService, "fanOutService不能为空");
        this.properties = Objects.requireNonNull(properties, "properties不能为空");
    }

    public Result query(Command command, BooleanSupplier cancellationRequested) {
        validate(command, cancellationRequested);
        try {
            String departmentId = selectionTokenService.resolve(
                    command.departmentSelectionToken(), command.userId(), command.sessionId(),
                    BusinessSubjectType.DEPARTMENT
            ).orElse(null);
            if (departmentId == null || !departmentStillAuthorized(command, departmentId)) {
                return Result.denied();
            }

            // 配置只能调低单次人数，不能突破部门同步查询的硬上限。
            int effectiveLimit = Math.min(properties.getMaxPeople(), HARD_MAX_PEOPLE);
            if (effectiveLimit <= 0) {
                return Result.denied();
            }
            int pageSize = Math.min(50, effectiveLimit);
            SubjectDirectoryPage page = memberDirectoryService.search(memberQuery(command, departmentId, 1, pageSize));
            if (page == null || !page.accessible()) {
                return Result.denied();
            }
            if (page.totalCount() > effectiveLimit) {
                return Result.limitExceeded(page.totalCount());
            }

            int expectedTotal = Math.toIntExact(page.totalCount());
            int maxPage = Math.max(1, (expectedTotal + pageSize - 1) / pageSize);
            Map<String, AuthorizedSubjectCandidate> members = new LinkedHashMap<>();
            for (int pageNumber = 1; pageNumber <= maxPage; pageNumber++) {
                if (pageNumber > 1) {
                    page = memberDirectoryService.search(memberQuery(command, departmentId, pageNumber, pageSize));
                }
                int expectedPageCount = Math.min(pageSize, expectedTotal - (pageNumber - 1) * pageSize);
                if (page == null || !page.accessible()
                        || page.pageNumber() != pageNumber || page.pageSize() != pageSize
                        || page.totalCount() != expectedTotal
                        || page.hasNext() != (pageNumber < maxPage)
                        || page.candidates().size() != expectedPageCount) {
                    return Result.denied();
                }
                for (AuthorizedSubjectCandidate person : page.candidates()) {
                    if (person.type() != BusinessSubjectType.PERSON
                            || !StringUtils.hasText(person.maskedEmployeeNo())
                            || members.putIfAbsent(person.rawSubjectId(), person) != null) {
                        return Result.denied();
                    }
                }
            }
            if (members.size() != expectedTotal) {
                return Result.denied();
            }
            if (members.isEmpty()) {
                return new Result(DepartmentQueryStatus.COMPLETED, true, 0, 0, 0,
                        emptyAggregate(command.plans()),
                        Map.of(), List.of(), null);
            }

            List<PersonRequest> requests = new ArrayList<>(members.size());
            for (AuthorizedSubjectCandidate person : members.values()) {
                String token = selectionTokenService.issue(person.rawSubjectId(), command.userId(),
                        command.sessionId(), BusinessSubjectType.PERSON);
                requests.add(new PersonRequest(
                        person.displayName() + "（" + person.maskedEmployeeNo() + "）",
                        command.anomalyPeopleRequested(),
                        new PersonBusinessQueryService.Command(command.agentRunId(), command.userId(),
                                command.sessionId(), command.authorization(), command.secureContext(),
                                token, command.refreshRequested(), command.plans())));
            }
            MultiPersonSummary summary = fanOutService.query(requests, cancellationRequested);
            EnumMap<PersonQueryStatus, Integer> counts = new EnumMap<>(PersonQueryStatus.class);
            for (MultiPersonSummary.PersonStatus person : summary.people()) {
                counts.merge(person.status(), 1, Integer::sum);
            }
            DepartmentQueryStatus status = switch (summary.status()) {
                case COMPLETED -> DepartmentQueryStatus.COMPLETED;
                case PARTIAL -> DepartmentQueryStatus.PARTIAL;
                case CANCELLED -> DepartmentQueryStatus.CANCELLED;
                case LIMIT_EXCEEDED -> DepartmentQueryStatus.LIMIT_EXCEEDED;
            };
            // processedPeople 表示已有终态记录数，包含取消、超时，不代表实际启动调用数。
            return new Result(status, status == DepartmentQueryStatus.COMPLETED && summary.aggregate().complete(),
                    expectedTotal, members.size(), summary.people().size(), summary.aggregate(), counts,
                    command.anomalyPeopleRequested() ? summary.anomalyPeople() : List.of(),
                    status == DepartmentQueryStatus.LIMIT_EXCEEDED ? LIMIT_MESSAGE : null);
        } catch (RuntimeException ignored) {
            // 下游异常统一失败关闭，异常消息和主体标识均不进入结果或日志。
            return Result.denied();
        }
    }

    private boolean departmentStillAuthorized(Command command, String departmentId) {
        SubjectDirectoryPage page = departmentDirectoryService.search(new SubjectDirectoryQuery(
                command.agentRunId(), command.userId(), command.sessionId(), command.authorization(),
                command.secureContext(), BusinessSubjectType.DEPARTMENT, SubjectSearchMode.SELECTED_SUBJECT,
                departmentId, null, null, null, null, null, 1, 2));
        return page != null && page.accessible() && page.totalCount() == 1 && !page.hasNext()
                && page.pageNumber() == 1 && page.pageSize() == 2 && page.candidates().size() == 1
                && page.candidates().get(0).type() == BusinessSubjectType.DEPARTMENT
                && departmentId.equals(page.candidates().get(0).rawSubjectId());
    }

    private DepartmentMemberDirectoryQuery memberQuery(Command command, String departmentId, int pageNumber, int pageSize) {
        return new DepartmentMemberDirectoryQuery(command.agentRunId(), command.userId(), command.sessionId(),
                command.authorization(), command.secureContext(), departmentId, pageNumber, pageSize);
    }

    /** 空部门也只为用户实际请求的正式汇总字段返回零值。 */
    private Aggregate emptyAggregate(List<DatasetPlan> plans) {
        boolean travelRequested = plans.stream().anyMatch(
                plan -> plan.userRequested() && plan.type() == DatasetType.TRAVEL
        );
        boolean reimbursementRequested = plans.stream().anyMatch(
                plan -> plan.userRequested() && plan.type() == DatasetType.REIMBURSEMENT
        );
        return new Aggregate(
                true,
                0,
                travelRequested ? 0 : null,
                travelRequested ? BigDecimal.ZERO : null,
                reimbursementRequested ? BigDecimal.ZERO : null
        );
    }

    private void validate(Command command, BooleanSupplier cancellationRequested) {
        if (command == null || cancellationRequested == null) {
            throw new IllegalArgumentException("部门业务查询命令不完整");
        }
        SubjectRequestLimits.requireText(command.agentRunId(), "agentRunId", 128);
        SubjectRequestLimits.requireText(command.userId(), "userId", 256);
        SubjectRequestLimits.requireText(command.sessionId(), "sessionId", 256);
        SubjectRequestLimits.requireText(command.authorization(), "authorization", 32768);
        SubjectRequestLimits.requireText(command.departmentSelectionToken(), "departmentSelectionToken", 4096);
        SubjectRequestLimits.validateContext(command.secureContext());
        if (command.plans().isEmpty()
                || command.plans().size() > DatasetType.values().length) {
            throw new IllegalArgumentException("部门业务查询数据集计划不完整");
        }
        Set<DatasetType> types = EnumSet.noneOf(DatasetType.class);
        Set<String> codes = new HashSet<>();
        boolean userRequested = false;
        for (DatasetPlan plan : command.plans()) {
            if (plan == null || plan.type() == null || !types.add(plan.type())
                    || !StringUtils.hasText(plan.datasetCode()) || !codes.add(plan.datasetCode())
                    || plan.requiredFactCodes().isEmpty()) {
                throw new IllegalArgumentException("部门业务查询数据集计划不完整或重复");
            }
            userRequested |= plan.userRequested();
        }
        if (!userRequested) {
            throw new IllegalArgumentException("部门业务查询缺少用户请求数据集");
        }
    }

    public enum DepartmentQueryStatus {
        COMPLETED, PARTIAL, CANCELLED, LIMIT_EXCEEDED, DENIED
    }

    /** 安全上下文深冻结，所有成员共享同一组不可变的数据集和时间范围计划。 */
    public record Command(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String departmentSelectionToken,
            boolean refreshRequested,
            boolean anomalyPeopleRequested,
            List<DatasetPlan> plans) {

        @SuppressWarnings("unchecked")
        public Command {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext);
            plans = plans == null ? List.of() : List.copyOf(plans);
        }

        @Override
        public String toString() {
            return "Command[authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", selectionTokenPresent=" + StringUtils.hasText(departmentSelectionToken)
                    + ", refreshRequested=" + refreshRequested
                    + ", anomalyPeopleRequested=" + anomalyPeopleRequested
                    + ", planCount=" + plans.size() + ']';
        }
    }

    /** 不携带全员列表、内部标识、选择令牌或单人事实。 */
    public record Result(
            DepartmentQueryStatus status,
            boolean dataComplete,
            long authorizedPeople,
            int loadedPeople,
            int processedPeople,
            Aggregate aggregate,
            Map<PersonQueryStatus, Integer> statusCounts,
            List<AnomalyPerson> anomalyPeople,
            String safeMessage) {

        public Result {
            EnumMap<PersonQueryStatus, Integer> counts = new EnumMap<>(PersonQueryStatus.class);
            for (PersonQueryStatus personStatus : PersonQueryStatus.values()) {
                counts.put(personStatus, statusCounts.getOrDefault(personStatus, 0));
            }
            statusCounts = Map.copyOf(counts);
            anomalyPeople = List.copyOf(anomalyPeople);
        }

        /** 日志仅输出状态和数量，不输出人员标签、异常明细、金额或提示正文。 */
        @Override
        public String toString() {
            return "Result[status=" + status
                    + ", dataComplete=" + dataComplete
                    + ", authorizedPeople=" + authorizedPeople
                    + ", loadedPeople=" + loadedPeople
                    + ", processedPeople=" + processedPeople
                    + ", statusCount=" + statusCounts.size()
                    + ", anomalyCount=" + anomalyPeople.size() + ']';
        }

        private static Result denied() {
            return new Result(DepartmentQueryStatus.DENIED, false, 0, 0, 0,
                    new Aggregate(false, 0, null, null, null), Map.of(), List.of(), DENIED_MESSAGE);
        }

        private static Result limitExceeded(long authorizedPeople) {
            return new Result(DepartmentQueryStatus.LIMIT_EXCEEDED, false, authorizedPeople, 0, 0,
                    new Aggregate(false, 0, null, null, null), Map.of(), List.of(), LIMIT_MESSAGE);
        }
    }
}
