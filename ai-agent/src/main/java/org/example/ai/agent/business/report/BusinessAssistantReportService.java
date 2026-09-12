package org.example.ai.agent.business.report;

import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.snapshot.BusinessSnapshotReferenceValidationService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.chat.protocol.block.ArtifactBlock;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 将业务查询产生的安全快照引用组装为组合报告任务。
 *
 * 本服务只解析不透明主体选择令牌和安全模块结果，不读取原始业务事实。
 */
@Service
public class BusinessAssistantReportService {

    private static final String PERSON_TEMPLATE_CODE = "PERSON_STANDARD";
    private static final String TRAVEL_UNAVAILABLE_MESSAGE = "出差数据源尚未配置，本次未纳入统计";
    private static final String ATTENDANCE_UNAVAILABLE_MESSAGE = "考勤数据源尚未配置，本次未纳入统计";
    private static final String REIMBURSEMENT_UNAVAILABLE_MESSAGE = "报销数据源尚未配置，本次未纳入统计";
    private static final Set<String> PERSON_UNAVAILABLE_MESSAGES = Set.of(
            TRAVEL_UNAVAILABLE_MESSAGE,
            ATTENDANCE_UNAVAILABLE_MESSAGE,
            REIMBURSEMENT_UNAVAILABLE_MESSAGE
    );
    private static final Set<String> RESOLVED_SECTION_STATUSES = Set.of(
            "REUSED", "QUERIED", "EMPTY"
    );

    private final SubjectSelectionTokenService selectionTokenService;
    private final ReportDatasetService reportDatasetService;
    private final BusinessSnapshotReferenceValidationService snapshotReferenceValidationService;
    private final CompositeReportTaskService reportTaskService;

    public BusinessAssistantReportService(
            SubjectSelectionTokenService selectionTokenService,
            ReportDatasetService reportDatasetService,
            BusinessSnapshotReferenceValidationService snapshotReferenceValidationService,
            CompositeReportTaskService reportTaskService) {
        this.selectionTokenService = Objects.requireNonNull(
                selectionTokenService, "selectionTokenService不能为空"
        );
        this.reportDatasetService = Objects.requireNonNull(
                reportDatasetService, "reportDatasetService不能为空"
        );
        this.snapshotReferenceValidationService = Objects.requireNonNull(
                snapshotReferenceValidationService, "snapshotReferenceValidationService不能为空"
        );
        this.reportTaskService = Objects.requireNonNull(
                reportTaskService, "reportTaskService不能为空"
        );
    }

    /** 使用项目全景的既定模块顺序和安全快照引用创建报告。 */
    public ArtifactBlock createProjectReport(ProjectReportCommand command) {
        Objects.requireNonNull(command, "项目报告命令不能为空");
        ProjectPanoramaResult panorama = Objects.requireNonNull(
                command.panorama(), "项目全景结果不能为空"
        );
        String projectType = normalizeProjectType(command.projectType());
        String subjectId = resolveSubject(command.identity(), BusinessSubjectType.PROJECT);
        Map<String, Object> snapshotQuery = projectSnapshotQuery(
                command.canonicalQuery(), command.projectCode()
        );
        validateProjectReferences(command.identity(), subjectId, snapshotQuery, panorama.modules());
        Map<String, ReportDataset> datasets = datasetDefinitions();
        Map<String, ProjectPanoramaResult.ModuleResult> modules = index(
                panorama.modules(), ProjectPanoramaResult.ModuleResult::datasetCode
        );
        BusinessReportPlanService planService = new BusinessReportPlanService(
                (type, ignored) -> new BusinessReportPlanService.TemplateDefinition(
                        "PROJECT_" + projectType,
                        panorama.profileChecksum(),
                        templateSections(panorama.modules().stream()
                                .map(ProjectPanoramaResult.ModuleResult::datasetCode).toList())
                ),
                section -> projectSection(modules.get(section.datasetCode()), datasets)
        );
        BusinessReportPlanService.PlanCommand planCommand = planCommand(
                command.identity(), BusinessSubjectType.PROJECT, subjectId,
                projectType, command.format(), command.canonicalQuery(),
                command.refreshRequested()
        );
        BusinessReportPlanService.PlannedReport planned = planService.plan(planCommand);
        return createArtifact(
                command.identity(), command.format(), command.canonicalQuery(), planned
        );
    }

    /** 将缺失业务转为固定提示，附加到首个有效章节并标记报告数据不完整。 */
    private BusinessReportPlanService.PlannedReport withUnavailablePersonDisclosure(
            BusinessReportPlanService.PlannedReport planned,
            List<String> unavailableSemanticCodes) {
        String message = unavailablePersonMessage(unavailableSemanticCodes);
        if (!StringUtils.hasText(message)) {
            return planned;
        }
        BusinessReportPlanService.LogicalReportPlan source = planned.plan();
        List<BusinessReportPlanService.LogicalReportSection> sections =
                new ArrayList<>(source.sections());
        for (int index = 0; index < sections.size(); index++) {
            BusinessReportPlanService.LogicalReportSection section = sections.get(index);
            if (!RESOLVED_SECTION_STATUSES.contains(section.status())) {
                continue;
            }
            sections.set(index, new BusinessReportPlanService.LogicalReportSection(
                    section.datasetCode(), section.snapshotId(), section.fieldPolicyChecksum(),
                    section.status(), message
            ));
            BusinessReportPlanService.LogicalReportPlan disclosed =
                    new BusinessReportPlanService.LogicalReportPlan(
                            source.templateCode(), source.subjectType(), source.subjectId(),
                            source.format(), List.copyOf(sections), false
                    );
            return new BusinessReportPlanService.PlannedReport(
                    disclosed, planned.templateChecksum()
            );
        }
        throw new IllegalStateException("人员报告没有可用于披露缺失数据的有效章节");
    }

    private String unavailablePersonMessage(List<String> semanticCodes) {
        if (semanticCodes == null || semanticCodes.isEmpty()) {
            return null;
        }
        return semanticCodes.stream()
                .distinct()
                .map(this::unavailablePersonMessage)
                .collect(Collectors.joining("；"));
    }

    private String unavailablePersonMessage(String semanticCode) {
        return switch (Objects.toString(semanticCode, "")) {
            case "TRAVEL" -> TRAVEL_UNAVAILABLE_MESSAGE;
            case "ATTENDANCE" -> ATTENDANCE_UNAVAILABLE_MESSAGE;
            case "REIMBURSEMENT" -> REIMBURSEMENT_UNAVAILABLE_MESSAGE;
            default -> throw new IllegalArgumentException("人员报告存在未知的不可用业务语义");
        };
    }

    /** 只允许由固定人员业务提示组成的安全说明，禁止写入原始错误文本。 */
    static boolean isPersonUnavailableMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String[] parts = message.split("；", -1);
        Set<String> unique = new HashSet<>();
        for (String part : parts) {
            if (!PERSON_UNAVAILABLE_MESSAGES.contains(part) || !unique.add(part)) {
                return false;
            }
        }
        return true;
    }

    /** 使用人员查询返回的安全模块引用创建报告，失败章节保持失败关闭。 */
    public ArtifactBlock createPersonReport(PersonReportCommand command) {
        Objects.requireNonNull(command, "人员报告命令不能为空");
        String subjectId = resolveSubject(command.identity(), BusinessSubjectType.PERSON);
        Map<String, Object> snapshotQuery = personSnapshotQuery(
                command.canonicalQuery(), subjectId
        );
        validatePersonReferences(command.identity(), subjectId, snapshotQuery, command.modules());
        Map<String, ReportDataset> datasets = datasetDefinitions();
        Map<String, PersonBusinessQueryService.ModuleResult> modules = index(
                command.modules(), PersonBusinessQueryService.ModuleResult::datasetCode
        );
        List<String> datasetCodes = command.modules().stream()
                .map(PersonBusinessQueryService.ModuleResult::datasetCode)
                .toList();
        String templateChecksum = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(datasetCodes.stream()
                        .map(code -> Map.of(
                                "datasetCode", code,
                                "fieldPolicyChecksum", configuredChecksum(datasets.get(code))
                        ))
                        .toList())
        );
        BusinessReportPlanService planService = new BusinessReportPlanService(
                (type, ignored) -> new BusinessReportPlanService.TemplateDefinition(
                        PERSON_TEMPLATE_CODE, templateChecksum, templateSections(datasetCodes)
                ),
                section -> personSection(modules.get(section.datasetCode()), datasets)
        );
        BusinessReportPlanService.PlanCommand planCommand = planCommand(
                command.identity(), BusinessSubjectType.PERSON, subjectId,
                null, command.format(), command.canonicalQuery(),
                command.refreshRequested()
        );
        BusinessReportPlanService.PlannedReport planned = withUnavailablePersonDisclosure(
                planService.plan(planCommand), command.unavailableSemanticCodes()
        );
        return createArtifact(
                command.identity(), command.format(), command.canonicalQuery(), planned
        );
    }

    private String resolveSubject(ReportIdentity identity, BusinessSubjectType subjectType) {
        return selectionTokenService.resolve(
                identity.selectionToken(), identity.userId(), identity.sessionId(), subjectType
        ).orElseThrow(() -> new IllegalStateException("主体选择已失效，无法创建报告"));
    }

    private void validateProjectReferences(
            ReportIdentity identity,
            String subjectId,
            Map<String, Object> snapshotQuery,
            List<ProjectPanoramaResult.ModuleResult> modules) {
        for (ProjectPanoramaResult.ModuleResult module : modules) {
            if (module.status() != DatasetExecutionStatus.SUCCESS
                    && module.status() != DatasetExecutionStatus.EMPTY) {
                continue;
            }
            validateReference(
                    identity, BusinessSubjectType.PROJECT, subjectId, module.datasetCode(),
                    module.snapshotId(), module.fieldPolicyChecksum(), snapshotQuery
            );
        }
    }

    private void validatePersonReferences(
            ReportIdentity identity,
            String subjectId,
            Map<String, Object> snapshotQuery,
            List<PersonBusinessQueryService.ModuleResult> modules) {
        for (PersonBusinessQueryService.ModuleResult module : modules) {
            if (module.status() != PersonBusinessQueryService.ModuleStatus.REUSED
                    && module.status() != PersonBusinessQueryService.ModuleStatus.SUCCESS
                    && module.status() != PersonBusinessQueryService.ModuleStatus.EMPTY) {
                continue;
            }
            validateReference(
                    identity, BusinessSubjectType.PERSON, subjectId, module.datasetCode(),
                    module.snapshotId(), module.fieldPolicyChecksum(), snapshotQuery
            );
        }
    }

    private void validateReference(
            ReportIdentity identity,
            BusinessSubjectType subjectType,
            String subjectId,
            String datasetCode,
            String snapshotId,
            String fieldPolicyChecksum,
            Map<String, Object> snapshotQuery) {
        boolean valid = snapshotReferenceValidationService.validate(
                new BusinessSnapshotReferenceValidationService.ValidationCommand(
                        identity.agentRunId(), identity.userId(), identity.sessionId(),
                        identity.authorization(), identity.secureContext(), subjectType, subjectId,
                        datasetCode, snapshotId, fieldPolicyChecksum, snapshotQuery
                )
        );
        if (!valid) {
            throw new IllegalStateException("报告章节快照引用已失效");
        }
    }

    private Map<String, Object> projectSnapshotQuery(
            Map<String, Object> canonicalQuery,
            String projectCode) {
        Map<String, Object> query = new LinkedHashMap<>(canonicalQuery);
        query.remove("projectYear");
        query.put("projectCode", projectCode);
        return safeMap(query);
    }

    private Map<String, Object> personSnapshotQuery(
            Map<String, Object> canonicalQuery,
            String subjectId) {
        Map<String, Object> query = new LinkedHashMap<>(canonicalQuery);
        query.put("employeeNo", subjectId);
        return safeMap(query);
    }

    private BusinessReportPlanService.PlanCommand planCommand(
            ReportIdentity identity,
            BusinessSubjectType subjectType,
            String subjectId,
            String projectType,
            String format,
            Map<String, Object> canonicalQuery,
            boolean refreshRequested) {
        return new BusinessReportPlanService.PlanCommand(
                identity.agentRunId(), identity.userId(), identity.sessionId(),
                identity.authorization(), identity.secureContext(), subjectType, subjectId,
                projectType, normalizeFormat(format), canonicalQuery,
                List.of(), List.of(), refreshRequested
        );
    }

    private ArtifactBlock createArtifact(
            ReportIdentity identity,
            String format,
            Map<String, Object> canonicalQuery,
            BusinessReportPlanService.PlannedReport planned) {
        CompositeReportTask task = reportTaskService.create(
                new CompositeReportTaskService.CreateCommand(
                        identity.userId(), identity.sessionId(), identity.authorization(),
                        planned, canonicalQuery, LocalDateTime.now().plusHours(24), 3
                )
        );
        return new ArtifactBlock(
                "report_artifact", "报告文件", 100, BlockStatus.PENDING, BlockSource.SYSTEM,
                task.getTaskId(), normalizeFormat(format), "", task.getStatus(), task.getExpiresAt(),
                false, "报告已进入生成队列"
        );
    }

    private BusinessReportPlanService.ResolvedSection projectSection(
            ProjectPanoramaResult.ModuleResult module,
            Map<String, ReportDataset> datasets) {
        if (module == null) {
            throw new IllegalStateException("报告章节不在项目全景模块中");
        }
        BusinessReportPlanService.SectionResolutionStatus status = switch (module.status()) {
            case SUCCESS -> BusinessReportPlanService.SectionResolutionStatus.QUERIED;
            case EMPTY -> BusinessReportPlanService.SectionResolutionStatus.EMPTY;
            case DENIED -> BusinessReportPlanService.SectionResolutionStatus.DENIED;
            default -> BusinessReportPlanService.SectionResolutionStatus.FAILED;
        };
        return resolvedSection(
                module.datasetCode(), module.snapshotId(), module.fieldPolicyChecksum(),
                datasets.get(module.datasetCode()), status, module.dataComplete()
        );
    }

    private BusinessReportPlanService.ResolvedSection personSection(
            PersonBusinessQueryService.ModuleResult module,
            Map<String, ReportDataset> datasets) {
        if (module == null) {
            throw new IllegalStateException("报告章节不在人员查询模块中");
        }
        BusinessReportPlanService.SectionResolutionStatus status = switch (module.status()) {
            case REUSED -> BusinessReportPlanService.SectionResolutionStatus.REUSED;
            case SUCCESS -> BusinessReportPlanService.SectionResolutionStatus.QUERIED;
            case EMPTY -> BusinessReportPlanService.SectionResolutionStatus.EMPTY;
            case DENIED -> BusinessReportPlanService.SectionResolutionStatus.DENIED;
            case FAILED, TIMEOUT -> BusinessReportPlanService.SectionResolutionStatus.FAILED;
        };
        return resolvedSection(
                module.datasetCode(), module.snapshotId(), module.fieldPolicyChecksum(),
                datasets.get(module.datasetCode()), status, module.complete()
        );
    }

    private BusinessReportPlanService.ResolvedSection resolvedSection(
            String datasetCode,
            String snapshotId,
            String moduleChecksum,
            ReportDataset configured,
            BusinessReportPlanService.SectionResolutionStatus status,
            boolean dataComplete) {
        boolean resolved = status == BusinessReportPlanService.SectionResolutionStatus.REUSED
                || status == BusinessReportPlanService.SectionResolutionStatus.QUERIED
                || status == BusinessReportPlanService.SectionResolutionStatus.EMPTY;
        // 已完成章节必须使用产生该快照时的策略校验和，禁止用当前配置掩盖引用缺损。
        String checksum = resolved ? moduleChecksum : configuredChecksum(configured);
        return new BusinessReportPlanService.ResolvedSection(
                datasetCode, resolved ? snapshotId : null, checksum,
                status, resolved && dataComplete, null
        );
    }

    private String configuredChecksum(ReportDataset dataset) {
        if (dataset == null || !StringUtils.hasText(dataset.getFieldPolicyChecksum())) {
            throw new IllegalStateException("报告章节缺少已注册字段策略校验和");
        }
        return dataset.getFieldPolicyChecksum();
    }

    private Map<String, ReportDataset> datasetDefinitions() {
        Map<String, ReportDataset> definitions = new LinkedHashMap<>();
        List<ReportDataset> source = reportDatasetService.list();
        if (source != null) {
            source.stream()
                    .filter(Objects::nonNull)
                    .filter(dataset -> Boolean.TRUE.equals(dataset.getEnabled()))
                    .filter(dataset -> StringUtils.hasText(dataset.getDatasetCode()))
                    .forEach(dataset -> definitions.putIfAbsent(dataset.getDatasetCode(), dataset));
        }
        return Map.copyOf(definitions);
    }

    private <T> Map<String, T> index(List<T> source, Function<T, String> code) {
        Map<String, T> indexed = new LinkedHashMap<>();
        for (T value : source == null ? List.<T>of() : source) {
            if (value == null || indexed.putIfAbsent(code.apply(value), value) != null) {
                throw new IllegalArgumentException("报告模块为空或编码重复");
            }
        }
        if (indexed.isEmpty()) {
            throw new IllegalArgumentException("报告至少需要一个模块");
        }
        return Map.copyOf(indexed);
    }

    private List<BusinessReportPlanService.TemplateSection> templateSections(List<String> codes) {
        return IntStream.range(0, codes.size())
                .mapToObj(index -> new BusinessReportPlanService.TemplateSection(
                        codes.get(index), index + 1
                ))
                .toList();
    }

    private String normalizeProjectType(String projectType) {
        return StringUtils.hasText(projectType)
                ? projectType.trim().toUpperCase(Locale.ROOT) : "DEFAULT";
    }

    private String normalizeFormat(String format) {
        return StringUtils.hasText(format) ? format.trim().toUpperCase(Locale.ROOT) : null;
    }

    public record ReportIdentity(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String selectionToken) {

        public ReportIdentity {
            secureContext = safeMap(secureContext);
        }

        /** 日志不输出认证、选择令牌或安全上下文内容。 */
        @Override
        public String toString() {
            return "ReportIdentity[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", selectionTokenPresent=" + StringUtils.hasText(selectionToken) + ']';
        }
    }

    public record ProjectReportCommand(
            ReportIdentity identity,
            String projectType,
            String projectCode,
            String format,
            Map<String, Object> canonicalQuery,
            boolean refreshRequested,
            ProjectPanoramaResult panorama) {

        public ProjectReportCommand {
            canonicalQuery = safeMap(canonicalQuery);
        }

        /** 日志只输出报告路由摘要。 */
        @Override
        public String toString() {
            return "ProjectReportCommand[format=" + format
                    + ", refreshRequested=" + refreshRequested
                    + ", canonicalQuerySize=" + canonicalQuery.size()
                    + ", panoramaPresent=" + (panorama != null) + ']';
        }
    }

    /**
     * 人员报告命令。
     *
     * projectContext 只记录本次查询已复权的项目标识和有效期，
     * 供报告任务登记当次查询范围；它不进入模型上下文，也不包含任何令牌。
     */
    public record PersonReportCommand(
            ReportIdentity identity,
            String format,
            Map<String, Object> canonicalQuery,
            boolean refreshRequested,
            List<PersonBusinessQueryService.ModuleResult> modules,
            List<String> unavailableSemanticCodes,
            PersonBusinessQueryService.ProjectAssociationContext projectContext) {

        public PersonReportCommand {
            canonicalQuery = safeMap(canonicalQuery);
            modules = modules == null ? List.of() : List.copyOf(modules);
            unavailableSemanticCodes = unavailableSemanticCodes == null
                    ? List.of() : List.copyOf(unavailableSemanticCodes);
        }

        /** 日志只输出报告路由摘要。 */
        @Override
        public String toString() {
            return "PersonReportCommand[format=" + format
                    + ", refreshRequested=" + refreshRequested
                    + ", canonicalQuerySize=" + canonicalQuery.size()
                    + ", moduleCount=" + modules.size()
                    + ", unavailableSemanticCount=" + unavailableSemanticCodes.size()
                    + ", projectContextPresent=" + (projectContext != null) + ']';
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeMap(Map<String, Object> source) {
        Object frozen = ReportDatasetValidator.freezeSafeValue(
                source == null ? Map.of() : source
        );
        if (!(frozen instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("安全上下文或规范查询必须是Map");
        }
        return (Map<String, Object>) frozen;
    }
}
