package org.example.ai.agent.business.person;

import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.person.model.AttendanceDayResult;
import org.example.ai.agent.business.snapshot.BusinessSnapshotMatcher;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService;
import org.example.ai.agent.business.snapshot.SnapshotFactChannel;
import org.example.ai.agent.business.subject.AuthorizedPersonDirectoryService;
import org.example.ai.agent.business.subject.SubjectDirectoryQuery;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单人业务安全查询编排。
 *
 * 本服务只消费字段策略产出的 calculation 事实；来源原始响应既不读取，也不进入返回对象。
 */
@Service
public class PersonBusinessQueryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonBusinessQueryService.class);
    public static final String TRAVEL_RECORDS = "person_travel_records";
    public static final String PUNCH_RECORDS = "person_punch_records";
    public static final String LEAVE_RECORDS = "person_leave_records";
    public static final String SCHEDULE_RECORDS = "person_schedule_records";
    public static final String CALENDAR_RECORDS = "person_calendar_records";
    public static final String REIMBURSEMENT_RECORDS = "person_reimbursement_records";

    private static final String SNAPSHOT_ITEM_KEY = "person";
    private static final Set<DatasetType> ATTENDANCE_DATASETS = Set.of(
            DatasetType.CALENDAR,
            DatasetType.SCHEDULE,
            DatasetType.PUNCH,
            DatasetType.LEAVE,
            DatasetType.TRAVEL
    );
    private final SubjectSelectionTokenService selectionTokenService;
    private final AuthorizedPersonDirectoryService personDirectoryService;
    private final PersonSnapshotReuseService snapshotReuseService;
    private final ReportDatasetExecutionService executionService;
    private final BusinessSnapshotService snapshotService;
    private final PersonBusinessFactAggregator factAggregator;

    public PersonBusinessQueryService(
            SubjectSelectionTokenService selectionTokenService,
            AuthorizedPersonDirectoryService personDirectoryService,
            PersonSnapshotReuseService snapshotReuseService,
            ReportDatasetExecutionService executionService,
            BusinessSnapshotService snapshotService,
            AttendanceReconciliationService attendanceService) {
        this.selectionTokenService = Objects.requireNonNull(
                selectionTokenService, "selectionTokenService不能为空"
        );
        this.personDirectoryService = Objects.requireNonNull(
                personDirectoryService, "personDirectoryService不能为空"
        );
        this.snapshotReuseService = Objects.requireNonNull(
                snapshotReuseService, "snapshotReuseService不能为空"
        );
        this.executionService = Objects.requireNonNull(executionService, "executionService不能为空");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService不能为空");
        this.factAggregator = new PersonBusinessFactAggregator(attendanceService);
    }

    public Result query(Command command) {
        ValidatedCommand validated = validate(command);
        String selectedSubjectId = selectionTokenService.resolve(
                validated.selectionToken(),
                validated.userId(),
                validated.sessionId(),
                BusinessSubjectType.PERSON
        ).orElseThrow(() -> new IllegalArgumentException("人员主体选择已失效"));
        AuthorizedSubjectCandidate person = reauthorizePerson(validated, selectedSubjectId);

        EnumMap<DatasetType, PersonBusinessFactAggregator.SafeFacts> safeFacts =
                new EnumMap<>(DatasetType.class);
        List<ModuleResult> modules = new ArrayList<>(DatasetType.values().length);
        for (DatasetType type : DatasetType.values()) {
            DatasetPlan plan = validated.plans().get(type);
            ModuleData module = loadModule(validated, person.rawSubjectId(), plan);
            safeFacts.put(type, new PersonBusinessFactAggregator.SafeFacts(
                    module.complete(), module.calculation()
            ));
            modules.add(new ModuleResult(type, module.status(), module.complete()));
        }

        return new Result(
                factAggregator.travelSummary(safeFacts.get(DatasetType.TRAVEL)),
                factAggregator.reimbursementSummary(safeFacts.get(DatasetType.REIMBURSEMENT)),
                factAggregator.attendance(
                        safeFacts,
                        validated.plans().get(DatasetType.CALENDAR).canonicalInput()
                ),
                modules
        );
    }

    private ValidatedCommand validate(Command command) {
        if (command == null
                || !StringUtils.hasText(command.agentRunId())
                || !StringUtils.hasText(command.userId())
                || !StringUtils.hasText(command.sessionId())
                || !StringUtils.hasText(command.authorization())
                || !StringUtils.hasText(command.selectionToken())) {
            throw new IllegalArgumentException("人员业务查询命令不完整");
        }
        if (command.plans().size() != DatasetType.values().length) {
            throw new IllegalArgumentException("人员业务查询必须包含六类数据集计划");
        }
        EnumMap<DatasetType, DatasetPlan> plans = new EnumMap<>(DatasetType.class);
        Set<String> datasetCodes = new HashSet<>();
        for (DatasetPlan plan : command.plans()) {
            if (plan == null || plan.type() == null
                    || !StringUtils.hasText(plan.datasetCode())
                    || plans.putIfAbsent(plan.type(), plan) != null
                    || !datasetCodes.add(plan.datasetCode())) {
                throw new IllegalArgumentException("数据集逻辑类型和编码必须唯一");
            }
            if (!plan.requiredFactCodes().equals(Set.of(factCode(plan.type())))) {
                throw new IllegalArgumentException("数据集稳定事实绑定不完整");
            }
        }
        if (plans.size() != DatasetType.values().length) {
            throw new IllegalArgumentException("数据集逻辑类型不完整");
        }
        validateAttendancePlanConsistency(plans);
        return new ValidatedCommand(
                command.agentRunId(), command.userId(), command.sessionId(),
                command.authorization(), command.secureContext(), command.selectionToken(),
                command.refreshRequested(), plans
        );
    }

    private void validateAttendancePlanConsistency(
            EnumMap<DatasetType, DatasetPlan> plans) {
        Set<DateRange> ranges = new HashSet<>();
        Set<String> grains = new HashSet<>();
        int plansWithRange = 0;
        for (DatasetType type : ATTENDANCE_DATASETS) {
            DatasetPlan plan = plans.get(type);
            Map<String, Object> input = plan.canonicalInput();
            boolean hasStart = input.containsKey("startDate");
            boolean hasEnd = input.containsKey("endDate");
            if (hasStart != hasEnd) {
                throw new IllegalArgumentException("考勤数据集时间范围不完整");
            }
            if (hasStart) {
                plansWithRange++;
                Object startValue = input.get("startDate");
                Object endValue = input.get("endDate");
                if (!(startValue instanceof String start)
                        || !(endValue instanceof String end)) {
                    throw new IllegalArgumentException("考勤数据集时间范围不合法");
                }
                try {
                    DateRange range = new DateRange(LocalDate.parse(start), LocalDate.parse(end));
                    if (range.start().isAfter(range.end())) {
                        throw new IllegalArgumentException("考勤数据集时间范围不合法");
                    }
                    ranges.add(range);
                } catch (DateTimeException exception) {
                    throw new IllegalArgumentException("考勤数据集时间范围不合法");
                }
            }
            String grain = normalizeGrain(plan.requestedGrain());
            grains.add(grain == null ? "<NONE>" : grain);
        }
        if ((plansWithRange != 0 && plansWithRange != ATTENDANCE_DATASETS.size())
                || ranges.size() > 1) {
            throw new IllegalArgumentException("考勤数据集时间范围必须一致");
        }
        if (grains.size() > 1) {
            throw new IllegalArgumentException("考勤数据集请求粒度必须一致");
        }
    }

    private String normalizeGrain(String grain) {
        return StringUtils.hasText(grain)
                ? grain.trim().toUpperCase(Locale.ROOT)
                : null;
    }

    private AuthorizedSubjectCandidate reauthorizePerson(
            ValidatedCommand command,
            String selectedSubjectId) {
        SubjectDirectoryPage page = personDirectoryService.search(new SubjectDirectoryQuery(
                command.agentRunId(), command.userId(), command.sessionId(),
                command.authorization(), command.secureContext(), BusinessSubjectType.PERSON,
                SubjectSearchMode.SELECTED_SUBJECT, selectedSubjectId,
                null, null, null, null, selectedSubjectId, 1, 2
        ));
        boolean unique = page != null
                && page.accessible()
                && page.candidates().size() == 1
                && page.totalCount() == 1
                && !page.hasNext();
        if (!unique) {
            throw new IllegalArgumentException("人员主体当前不可查询");
        }
        AuthorizedSubjectCandidate person = page.candidates().get(0);
        if (person == null
                || person.type() != BusinessSubjectType.PERSON
                || !Objects.equals(person.rawSubjectId(), selectedSubjectId)) {
            throw new IllegalArgumentException("人员主体当前不可查询");
        }
        return person;
    }

    private ModuleData loadModule(
            ValidatedCommand command,
            String subjectId,
            DatasetPlan plan) {
        Map<String, Object> canonicalInput = authorizedInput(plan.canonicalInput(), subjectId);
        if (command.refreshRequested()) {
            return executeModule(command, subjectId, plan, canonicalInput);
        }
        PersonSnapshotReuseService.ReuseResult reused = snapshotReuseService.reuse(
                new BusinessSnapshotMatcher.MatchCommand(
                command.agentRunId(), command.userId(), command.sessionId(),
                command.authorization(), command.secureContext(), BusinessSubjectType.PERSON,
                subjectId, plan.datasetCode(), canonicalInput, AssociationType.DIRECT,
                plan.requestedGrain(), plan.requiredFactCodes(),
                Set.of(SnapshotFactChannel.CALCULATION), command.refreshRequested()
        )).orElse(null);
        if (reused != null) {
            return new ModuleData(
                    ModuleStatus.REUSED, reused.dataComplete(), reused.calculation()
            );
        }
        /* DERIVE 本阶段按 REQUERY 收口，避免在人员敏感事实上引入未经证明的裁剪逻辑。 */
        return executeModule(command, subjectId, plan, canonicalInput);
    }

    private ModuleData executeModule(
            ValidatedCommand command,
            String subjectId,
            DatasetPlan plan,
            Map<String, Object> canonicalInput) {
        DatasetExecutionResult result;
        try {
            result = executionService.execute(new DatasetExecutionRequest(
                    command.agentRunId(), command.userId(), command.sessionId(),
                    command.authorization(), command.secureContext(), plan.datasetCode(),
                    BusinessSubjectType.PERSON, subjectId, canonicalInput
            ));
        } catch (RuntimeException exception) {
            warnModuleFailure("EXECUTION", plan.datasetCode(), exception);
            return new ModuleData(ModuleStatus.FAILED, false, Map.of());
        }
        if (result == null) {
            LOGGER.warn(
                    "人员数据集模块失败 category={} datasetCode={} exceptionType={}",
                    "EXECUTION",
                    plan.datasetCode(),
                    "NullResult"
            );
            return new ModuleData(ModuleStatus.FAILED, false, Map.of());
        }
        ModuleStatus status = moduleStatus(result.status());
        if (result.status() != DatasetExecutionStatus.SUCCESS
                && result.status() != DatasetExecutionStatus.EMPTY) {
            return new ModuleData(status, false, Map.of());
        }
        try {
            snapshotService.create(new BusinessSnapshotService.CreateCommand(
                    command.userId(), command.sessionId(), BusinessSubjectType.PERSON,
                    subjectId, plan.datasetCode(), canonicalInput, null,
                    List.of(new BusinessSnapshotService.ItemCommand(
                            SNAPSHOT_ITEM_KEY, AssociationType.DIRECT, result,
                            result.status() == DatasetExecutionStatus.EMPTY ? 0 : 1,
                            result.status() == DatasetExecutionStatus.EMPTY ? 0 : 1,
                            0
                    ))
            ));
        } catch (RuntimeException exception) {
            warnModuleFailure("SNAPSHOT_CREATE", plan.datasetCode(), exception);
            return new ModuleData(ModuleStatus.FAILED, false, Map.of());
        }
        Map<String, Object> calculation = factAggregator.calculation(
                result.safeFacts(), plan.requiredFactCodes()
        );
        if (calculation == null) {
            return new ModuleData(ModuleStatus.FAILED, false, Map.of());
        }
        return new ModuleData(status, result.dataComplete(), calculation);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> authorizedInput(
            Map<String, Object> canonicalInput,
            String employeeNo) {
        Map<String, Object> authorized = new LinkedHashMap<>(canonicalInput);
        // 工号只采用刚刚通过来源目录复核的内部标识，覆盖任何调用方同名值。
        authorized.put("employeeNo", employeeNo);
        return (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(authorized);
    }

    private void warnModuleFailure(
            String category,
            String datasetCode,
            RuntimeException exception) {
        LOGGER.warn(
                "人员数据集模块失败 category={} datasetCode={} exceptionType={}",
                category,
                datasetCode,
                exception.getClass().getSimpleName()
        );
    }

    private ModuleStatus moduleStatus(DatasetExecutionStatus status) {
        if (status == null) {
            return ModuleStatus.FAILED;
        }
        return switch (status) {
            case SUCCESS -> ModuleStatus.SUCCESS;
            case EMPTY -> ModuleStatus.EMPTY;
            case DENIED -> ModuleStatus.DENIED;
            case TIMEOUT -> ModuleStatus.TIMEOUT;
            default -> ModuleStatus.FAILED;
        };
    }

    private static String factCode(DatasetType type) {
        return switch (type) {
            case TRAVEL -> TRAVEL_RECORDS;
            case PUNCH -> PUNCH_RECORDS;
            case LEAVE -> LEAVE_RECORDS;
            case SCHEDULE -> SCHEDULE_RECORDS;
            case CALENDAR -> CALENDAR_RECORDS;
            case REIMBURSEMENT -> REIMBURSEMENT_RECORDS;
        };
    }

    public enum DatasetType {
        TRAVEL,
        PUNCH,
        LEAVE,
        SCHEDULE,
        CALENDAR,
        REIMBURSEMENT
    }

    public enum ModuleStatus {
        REUSED,
        SUCCESS,
        EMPTY,
        DENIED,
        FAILED,
        TIMEOUT
    }

    public record DatasetPlan(
            DatasetType type,
            String datasetCode,
            Map<String, Object> canonicalInput,
            String requestedGrain,
            Set<String> requiredFactCodes) {

        @SuppressWarnings("unchecked")
        public DatasetPlan {
            canonicalInput = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    canonicalInput == null ? Map.of() : canonicalInput
            );
            requiredFactCodes = requiredFactCodes == null
                    ? Set.of()
                    : Set.copyOf(requiredFactCodes);
        }

        @Override
        public String toString() {
            return "DatasetPlan[type=" + type
                    + ", datasetCode=" + datasetCode
                    + ", canonicalInputSize=" + canonicalInput.size()
                    + ", requiredFactCodeCount=" + requiredFactCodes.size() + ']';
        }
    }

    public record Command(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String selectionToken,
            boolean refreshRequested,
            List<DatasetPlan> plans) {

        @SuppressWarnings("unchecked")
        public Command {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            plans = plans == null ? List.of() : List.copyOf(plans);
        }

        @Override
        public String toString() {
            return "Command[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", selectionTokenPresent=" + StringUtils.hasText(selectionToken)
                    + ", refreshRequested=" + refreshRequested
                    + ", planCount=" + plans.size() + ']';
        }
    }

    public record Metric<T>(boolean complete, T value) {

        public Metric {
            if (complete != (value != null)) {
                throw new IllegalArgumentException("完整指标必须有值，不完整指标不得伪造值");
            }
        }

        public static <T> Metric<T> complete(T value) {
            return new Metric<>(true, Objects.requireNonNull(value, "value不能为空"));
        }

        public static <T> Metric<T> incomplete() {
            return new Metric<>(false, null);
        }

        @Override
        public String toString() {
            return "Metric[complete=" + complete + ']';
        }
    }

    public record TravelSummary(
            Metric<Integer> tripCount,
            Metric<BigDecimal> totalAmount) {
    }

    public record ReimbursementSummary(
            Metric<BigDecimal> requestedAmount,
            Metric<BigDecimal> approvedAmount,
            Metric<BigDecimal> paidAmount) {
    }

    /** 返回对象不携带快照ID、内部工号、认证上下文或字段策略前的事实。 */
    public record Result(
            TravelSummary travelSummary,
            ReimbursementSummary reimbursementSummary,
            List<AttendanceDayResult> attendance,
            List<ModuleResult> modules) {

        public Result {
            attendance = attendance == null ? List.of() : List.copyOf(attendance);
            modules = modules == null ? List.of() : List.copyOf(modules);
        }
    }

    public record ModuleResult(DatasetType type, ModuleStatus status, boolean complete) {
    }

    private record ValidatedCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String selectionToken,
            boolean refreshRequested,
            EnumMap<DatasetType, DatasetPlan> plans) {

        @Override
        public String toString() {
            return "ValidatedCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", selectionTokenPresent=" + StringUtils.hasText(selectionToken)
                    + ", refreshRequested=" + refreshRequested
                    + ", planCount=" + plans.size() + ']';
        }
    }

    private record ModuleData(
            ModuleStatus status,
            boolean complete,
            Map<String, Object> calculation) {

        @Override
        public String toString() {
            return "ModuleData[status=" + status
                    + ", complete=" + complete
                    + ", calculationFactCount=" + calculation.size() + ']';
        }
    }

    private record DateRange(LocalDate start, LocalDate end) {
    }
}
