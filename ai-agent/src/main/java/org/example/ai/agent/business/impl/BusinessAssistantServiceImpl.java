package org.example.ai.agent.business.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.business.BusinessAssistantService;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.ColumnDefinition;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.ComposeCommand;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.DatasetAnswerInput;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.MetricDefinition;
import org.example.ai.agent.business.answer.DeterministicBusinessAnswerComposer.TableDefinition;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService;
import org.example.ai.agent.business.department.DepartmentBusinessQueryService.DepartmentQueryStatus;
import org.example.ai.agent.business.intent.BusinessQueryIntent;
import org.example.ai.agent.business.intent.BusinessQueryIntentResolver;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.ProjectPanoramaExecutionService;
import org.example.ai.agent.business.panorama.ProjectPanoramaSnapshotReuseService;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaProgressEvent;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetPlan;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.PersonDatasetSelectionService;
import org.example.ai.agent.business.person.PersonDatasetSelectionService.Selection;
import org.example.ai.agent.business.person.model.AttendanceDayResult;
import org.example.ai.agent.business.person.model.MultiPersonSummary.PersonQueryStatus;
import org.example.ai.agent.business.report.BusinessAssistantReportService;
import org.example.ai.agent.business.subject.SubjectResolutionService;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import org.example.ai.agent.chat.protocol.block.ArtifactBlock;
import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.block.StatusListBlock;
import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseContext;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.protocol.stream.BlockErrorPayload;
import org.example.ai.agent.chat.service.AiChatSessionService;
import org.example.ai.agent.chat.support.AgentStreamSession;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * 只读业务对话的薄编排层，权限、工作流执行、计算和报告持久化继续委托给现有服务。
 */
@Service
@Slf4j
public class BusinessAssistantServiceImpl implements BusinessAssistantService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String STATUS_BLOCK_ID = "dataset_status";

    private final BusinessQueryIntentResolver intentResolver;
    private final SubjectResolutionService subjectResolutionService;
    private final ProjectPanoramaExecutionService panoramaExecutionService;
    private final ProjectPanoramaSnapshotReuseService panoramaSnapshotReuseService;
    private final PersonBusinessQueryService personBusinessQueryService;
    private final PersonDatasetSelectionService personDatasetSelectionService;
    private final DepartmentBusinessQueryService departmentBusinessQueryService;
    private final ReportDatasetService reportDatasetService;
    private final DeterministicBusinessAnswerComposer answerComposer;
    private final BusinessAssistantReportService reportService;
    private final ConversationStateService conversationStateService;
    private final AiChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;

    public BusinessAssistantServiceImpl(
            BusinessQueryIntentResolver intentResolver,
            SubjectResolutionService subjectResolutionService,
            ProjectPanoramaExecutionService panoramaExecutionService,
            ProjectPanoramaSnapshotReuseService panoramaSnapshotReuseService,
            PersonBusinessQueryService personBusinessQueryService,
            PersonDatasetSelectionService personDatasetSelectionService,
            DepartmentBusinessQueryService departmentBusinessQueryService,
            ReportDatasetService reportDatasetService,
            DeterministicBusinessAnswerComposer answerComposer,
            BusinessAssistantReportService reportService,
            ConversationStateService conversationStateService,
            AiChatSessionService chatSessionService,
            ObjectMapper objectMapper) {
        this.intentResolver = Objects.requireNonNull(intentResolver, "intentResolver不能为空");
        this.subjectResolutionService = Objects.requireNonNull(
                subjectResolutionService, "subjectResolutionService不能为空"
        );
        this.panoramaExecutionService = Objects.requireNonNull(
                panoramaExecutionService, "panoramaExecutionService不能为空"
        );
        this.panoramaSnapshotReuseService = Objects.requireNonNull(
                panoramaSnapshotReuseService, "panoramaSnapshotReuseService不能为空"
        );
        this.personBusinessQueryService = Objects.requireNonNull(
                personBusinessQueryService, "personBusinessQueryService不能为空"
        );
        this.personDatasetSelectionService = Objects.requireNonNull(
                personDatasetSelectionService, "personDatasetSelectionService不能为空"
        );
        this.departmentBusinessQueryService = Objects.requireNonNull(
                departmentBusinessQueryService, "departmentBusinessQueryService不能为空"
        );
        this.reportDatasetService = Objects.requireNonNull(
                reportDatasetService, "reportDatasetService不能为空"
        );
        this.answerComposer = Objects.requireNonNull(answerComposer, "answerComposer不能为空");
        this.reportService = Objects.requireNonNull(reportService, "reportService不能为空");
        this.conversationStateService = Objects.requireNonNull(
                conversationStateService, "conversationStateService不能为空"
        );
        this.chatSessionService = Objects.requireNonNull(chatSessionService, "chatSessionService不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    @Override
    public void handle(AgentRequest request, AgentStreamSession stream, String agentRunId) {
        validate(request, stream, agentRunId);
        try {
            BusinessQueryIntent intent = inheritQueryContext(intentResolver.resolve(
                    request.getEffectiveQuestion(), modelContext(request, agentRunId)
            ), request);
            BusinessSubjectType subjectType = resolveSubjectType(intent, request);
            if (subjectType == null) {
                handleUnresolved(request, stream, agentRunId, intent, null,
                        new SubjectResolutionResult(
                                SubjectResolutionState.EMPTY, null, List.of(),
                                1, DEFAULT_PAGE_SIZE, 0, false,
                                "请明确要查询项目、人员还是部门信息"
                        ));
                return;
            }
            if (intent.subjectType() != subjectType) {
                intent = withSubjectType(intent, subjectType);
            }
            intent = inheritPersonDatasetContext(intent, request, subjectType);
            Selection personSelection = subjectType == BusinessSubjectType.PROJECT
                    ? null : personDatasetSelectionService.select(intent.datasetCodes());
            SubjectResolutionResult subject = subjectResolutionService.resolve(
                    subjectRequest(request, intent, agentRunId)
            );
            if (subject.state() != SubjectResolutionState.RESOLVED) {
                handleUnresolved(request, stream, agentRunId, intent, personSelection, subject);
                return;
            }
            SubjectCandidate resolved = subject.resolvedSubject();
            ResponseContext context = context(intent, resolved);
            startWithContext(stream, context);

            if (resolved.type() == BusinessSubjectType.PROJECT) {
                handleProject(request, stream, agentRunId, intent, resolved, context);
            } else if (resolved.type() == BusinessSubjectType.PERSON) {
                handlePerson(request, stream, agentRunId, intent, personSelection, resolved, context);
            } else {
                handleDepartment(request, stream, agentRunId, intent, personSelection, resolved, context);
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("业务助手处理失败", exception);
        }
    }

    private void handleUnresolved(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            BusinessQueryIntent intent,
            Selection personSelection,
            SubjectResolutionResult result) throws Exception {
        ResponseContext context = new ResponseContext(
                intent.subjectType() == null ? "" : intent.subjectType().name(),
                "", "", scopeLabel(intent), intent.periodStart(), intent.periodEnd(), LocalDateTime.now()
        );
        startWithContext(stream, context);
        if (result.state() == SubjectResolutionState.CANDIDATES) {
            saveCandidateState(request, runId, intent, personSelection, result.candidates());
            DatasetAnswerInput candidates = new DatasetAnswerInput(
                    "SUBJECT_CANDIDATES", "可选主体", DatasetExecutionStatus.SUCCESS, true,
                    Map.of("rows", candidateRows(result.candidates())), Map.of(), result.safeMessage()
            );
            TableDefinition table = new TableDefinition(
                    "subject_candidates", "请选择要查询的主体", "SUBJECT_CANDIDATES", "rows",
                    List.of(
                            column("displayName", "名称"),
                            column("projectCode", "项目编码"),
                            column("projectType", "项目类型"),
                            column("maskedEmployeeNo", "工号"),
                            column("selectionToken", "选择凭证")
                    )
            );
            finish(request, stream, response(
                    request, stream, runId, context, false,
                    List.of(candidates), List.of(), List.of(table), List.of(), null, false
            ), false);
            return;
        }
        DatasetExecutionStatus status = result.state() == SubjectResolutionState.DENIED
                ? DatasetExecutionStatus.DENIED : DatasetExecutionStatus.EMPTY;
        DatasetAnswerInput terminal = new DatasetAnswerInput(
                "SUBJECT_RESOLUTION", "主体定位", status, false,
                Map.of(), Map.of(), result.safeMessage()
        );
        finish(request, stream, response(
                request, stream, runId, context, false,
                List.of(terminal), List.of(), List.of(), List.of(), null, false
        ), false);
    }

    private void handleProject(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            BusinessQueryIntent intent,
            SubjectCandidate subject,
            ResponseContext context) throws Exception {
        Set<String> startedBlocks = new HashSet<>();
        StatusProgress progress = new StatusProgress(stream, datasetNames());
        ProjectPanoramaResult panorama = reuseProjectPanorama(request, runId, intent, subject);
        if (panorama == null) {
            panorama = panoramaExecutionService.execute(
                    new ProjectPanoramaCommand(
                            runId, request.getUserId(), request.getConversationId(),
                            request.getAuthorization(), Map.of(), subject.selectionToken(), canonicalQuery(intent)
                    ), progress::accept
            );
        }
        List<DatasetAnswerInput> datasets = new ArrayList<>(projectDatasets(panorama, progress.names()));
        ArtifactBlock artifact = null;
        if (StringUtils.hasText(intent.exportFormat())) {
            try {
                artifact = reportService.createProjectReport(
                        new BusinessAssistantReportService.ProjectReportCommand(
                                reportIdentity(request, runId, subject), subject.projectType(), subject.projectCode(),
                                intent.exportFormat(), canonicalQuery(intent), intent.refresh(), panorama
                        )
                );
            } catch (RuntimeException exception) {
                // 报告创建失败不清空已成功查询的项目业务数据。
                log.warn("业务报告任务创建失败，runId={}，errorType={}",
                        runId, exception.getClass().getSimpleName());
                datasets.add(failedDataset("PROJECT_REPORT", "报告任务创建失败，业务数据仍可查看"));
            }
        }
        AiResponse composed = response(
                request, stream, runId, context, panorama.allModulesComplete(), datasets,
                metricDefinitions(datasets), tableDefinitions(datasets), panorama.issues(), artifact, true
        );
        saveState(request, runId, subject, intent, panorama.aggregateSnapshotId(), List.of());
        finish(request, stream, composed, true, startedBlocks);
    }

    private void handlePerson(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            BusinessQueryIntent intent,
            Selection selection,
            SubjectCandidate subject,
            ResponseContext context) throws Exception {
        Set<String> startedBlocks = new HashSet<>();
        StatusProgress progress = new StatusProgress(stream, datasetNames());
        List<DatasetPlan> plans = personPlans(intent, selection);
        PersonBusinessQueryService.Result result = personBusinessQueryService.query(
                new PersonBusinessQueryService.Command(
                        runId, request.getUserId(), request.getConversationId(),
                        request.getAuthorization(), Map.of(), subject.selectionToken(), intent.refresh(), plans
                )
        );
        List<DatasetAnswerInput> datasets = personDatasets(plans, result);
        ArtifactBlock artifact = null;
        if (StringUtils.hasText(intent.exportFormat())) {
            try {
                artifact = reportService.createPersonReport(
                        new BusinessAssistantReportService.PersonReportCommand(
                                reportIdentity(request, runId, subject), intent.exportFormat(),
                                canonicalQuery(intent), intent.refresh(), result.modules()
                        )
                );
            } catch (RuntimeException exception) {
                // 报告创建失败不清空已成功查询的人员确定性事实。
                log.warn("人员报告任务创建失败，runId={}，errorType={}",
                        runId, exception.getClass().getSimpleName());
                datasets = new ArrayList<>(datasets);
                datasets.add(failedDataset("PERSON_REPORT", "报告任务创建失败，业务数据仍可查看"));
            }
        }
        boolean complete = datasets.stream().allMatch(DatasetAnswerInput::dataComplete)
                && (artifact != null || !StringUtils.hasText(intent.exportFormat()));
        AiResponse composed = response(
                request, stream, runId, context, complete, datasets,
                personMetrics(plans), personTables(plans), List.of(), artifact, false
        );
        saveState(request, runId, subject, intent, null, selection.semanticCodes());
        finish(request, stream, composed, false, startedBlocks);
    }

    /** 部门回答仅发布安全汇总，不向模型提供人员事实。 */
    private void handleDepartment(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            BusinessQueryIntent intent,
            Selection selection,
            SubjectCandidate subject,
            ResponseContext context) throws Exception {
        DepartmentBusinessQueryService.Result result = departmentBusinessQueryService.query(
                new DepartmentBusinessQueryService.Command(
                        runId, request.getUserId(), request.getConversationId(), request.getAuthorization(),
                        Map.of(), subject.selectionToken(), intent.refresh(), intent.anomalyPeopleRequested(),
                        personPlans(intent, selection)
                ), stream::shouldStopBusinessQuery
        );
        String code = "DEPARTMENT_SUMMARY";
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("authorizedPeople", result.authorizedPeople());
        facts.put("loadedPeople", result.loadedPeople());
        facts.put("processedPeople", result.processedPeople());
        facts.put("successPeople", result.statusCounts().get(PersonQueryStatus.SUCCESS));
        facts.put("partialPeople", result.statusCounts().get(PersonQueryStatus.PARTIAL));
        facts.put("failedPeople", result.statusCounts().get(PersonQueryStatus.FAILED));
        facts.put("timeoutPeople", result.statusCounts().get(PersonQueryStatus.TIMEOUT));
        facts.put("cancelledPeople", result.statusCounts().get(PersonQueryStatus.CANCELLED));
        List<MetricDefinition> metrics = new ArrayList<>(List.of(
                metric(code, "authorizedPeople", "授权人数", ValueType.NUMBER, "人"),
                metric(code, "loadedPeople", "已加载人数", ValueType.NUMBER, "人"),
                metric(code, "processedPeople", "已处理人数", ValueType.NUMBER, "人"),
                metric(code, "successPeople", "成功人数", ValueType.NUMBER, "人"),
                metric(code, "partialPeople", "部分完成人数", ValueType.NUMBER, "人"),
                metric(code, "failedPeople", "失败人数", ValueType.NUMBER, "人"),
                metric(code, "timeoutPeople", "超时人数", ValueType.NUMBER, "人"),
                metric(code, "cancelledPeople", "取消人数", ValueType.NUMBER, "人")
        ));
        // 不完整聚合不发布次数和金额，避免部分结果被误读为正式总数。
        if (result.aggregate().complete() && selection.requested(DatasetType.TRAVEL)) {
            facts.put("tripCount", result.aggregate().tripCount());
            facts.put("travelAmount", result.aggregate().travelAmount());
            metrics.add(metric(code, "tripCount", "出差次数", ValueType.NUMBER, "次"));
            metrics.add(metric(code, "travelAmount", "出差总金额", ValueType.AMOUNT, "元"));
        }
        if (result.aggregate().complete() && selection.requested(DatasetType.REIMBURSEMENT)) {
            facts.put("reimbursementAmount", result.aggregate().reimbursementAmount());
            metrics.add(metric(code, "reimbursementAmount", "报销总金额", ValueType.AMOUNT, "元"));
        }
        List<TableDefinition> tables = List.of();
        if (selection.requested(DatasetType.PUNCH)
                && intent.anomalyPeopleRequested() && !result.anomalyPeople().isEmpty()) {
            facts.put("anomalyRows", result.anomalyPeople().stream()
                    .map(person -> Map.of(
                            "displayLabel", person.displayLabel(),
                            "anomalyTypes", String.join("、", person.anomalyTypes())
                    )).toList());
            tables = List.of(new TableDefinition(
                    "department_anomalies", "部门异常人员", code, "anomalyRows",
                    List.of(column("displayLabel", "人员"), column("anomalyTypes", "异常类型"))
            ));
        }
        DatasetExecutionStatus status = switch (result.status()) {
            case COMPLETED -> DatasetExecutionStatus.SUCCESS;
            case DENIED -> DatasetExecutionStatus.DENIED;
            case PARTIAL, CANCELLED, LIMIT_EXCEEDED -> DatasetExecutionStatus.FAILED;
        };
        String message = result.safeMessage();
        if (result.status() == DepartmentQueryStatus.LIMIT_EXCEEDED && !StringUtils.hasText(message)) {
            message = "当前授权成员超过单次查询上限，请按下级部门或人员范围缩小查询";
        }
        List<DatasetAnswerInput> datasets = new ArrayList<>();
        datasets.add(new DatasetAnswerInput(code, "部门汇总", status, result.dataComplete(), facts, Map.of(), message));
        boolean complete = result.dataComplete();
        if (StringUtils.hasText(intent.exportFormat())) {
            datasets.add(failedDataset("DEPARTMENT_REPORT", "本阶段暂不支持部门报告导出，请缩小到单个人员后导出"));
            complete = false;
        }
        AiResponse composed = response(request, stream, runId, context, complete, datasets,
                metrics, tables, List.of(), null, false);
        saveState(request, runId, subject, intent, null, selection.semanticCodes());
        finish(request, stream, composed, false);
    }

    private ProjectPanoramaResult reuseProjectPanorama(
            AgentRequest request,
            String runId,
            BusinessQueryIntent intent,
            SubjectCandidate subject) {
        String snapshotId = trustedInheritedText(request, "panoramaSnapshotId");
        if (intent.refresh() || StringUtils.hasText(intent.projectCode())
                || !StringUtils.hasText(snapshotId)) {
            return null;
        }
        return panoramaSnapshotReuseService.reuse(
                new ProjectPanoramaSnapshotReuseService.ReuseCommand(
                        runId, request.getUserId(), request.getConversationId(),
                        request.getAuthorization(), Map.of(), subject.selectionToken(),
                        snapshotId, canonicalQuery(intent)
                )
        ).orElse(null);
    }

    private BusinessAssistantReportService.ReportIdentity reportIdentity(
            AgentRequest request,
            String runId,
            SubjectCandidate subject) {
        return new BusinessAssistantReportService.ReportIdentity(
                runId, request.getUserId(), request.getConversationId(),
                request.getAuthorization(), Map.of(), subject.selectionToken()
        );
    }

    private List<DatasetPlan> personPlans(BusinessQueryIntent intent, Selection selection) {
        Set<DatasetType> requiredTypes = Set.copyOf(selection.executionTypes());
        EnumMap<DatasetType, ReportDataset> configured = new EnumMap<>(DatasetType.class);
        for (ReportDataset dataset : safeList(reportDatasetService.list())) {
            DatasetType type = personType(dataset);
            if (type == null || !requiredTypes.contains(type)) {
                continue;
            }
            if (configured.putIfAbsent(type, dataset) != null) {
                throw new IllegalStateException("人员业务数据集逻辑类型配置重复：" + type.name());
            }
        }
        if (configured.size() != requiredTypes.size()) {
            throw new IllegalStateException("人员业务查询缺少完整且唯一的已启用数据集配置");
        }
        Map<String, Object> query = canonicalQuery(intent);
        return selection.executionTypes().stream()
                .map(type -> personPlan(type, configured, query, selection.requested(type)))
                .toList();
    }

    private DatasetPlan personPlan(
            DatasetType type,
            EnumMap<DatasetType, ReportDataset> configured,
            Map<String, Object> query,
            boolean userRequested) {
        return new DatasetPlan(
                type, configured.get(type).getDatasetCode(), query,
                query.containsKey("startDate") ? "DAY" : null, Set.of(personFactCode(type)),
                userRequested
        );
    }

    private DatasetType personType(ReportDataset dataset) {
        if (dataset == null || !Boolean.TRUE.equals(dataset.getEnabled())
                || !StringUtils.hasText(dataset.getDomainCode()) || !allowsPerson(dataset)) {
            return null;
        }
        String domain = dataset.getDomainCode().trim().toUpperCase(Locale.ROOT);
        for (DatasetType type : DatasetType.values()) {
            if (domain.equals(type.name()) || domain.equals("PERSON_" + type.name())) {
                return type;
            }
        }
        return null;
    }

    private boolean allowsPerson(ReportDataset dataset) {
        try {
            JsonNode types = objectMapper.readTree(dataset.getSubjectTypesJson());
            if (types == null || !types.isArray()) {
                return false;
            }
            for (JsonNode type : types) {
                if (type.isTextual() && "PERSON".equals(type.textValue().trim().toUpperCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<DatasetAnswerInput> projectDatasets(
            ProjectPanoramaResult panorama,
            Map<String, String> names) {
        return panorama.modules().stream().map(module -> {
            DatasetExecutionResult result = module.executionResult();
            return new DatasetAnswerInput(
                    module.datasetCode(), names.getOrDefault(module.datasetCode(), module.datasetCode()),
                    module.status(), module.dataComplete(), module.displayFacts(),
                    module.modelFacts(), safeMessage(module.status(), result)
            );
        }).toList();
    }

    private List<DatasetAnswerInput> personDatasets(
            List<DatasetPlan> plans,
            PersonBusinessQueryService.Result result) {
        Map<DatasetType, DatasetPlan> byType = new EnumMap<>(DatasetType.class);
        plans.forEach(plan -> byType.put(plan.type(), plan));
        boolean attendanceComplete = attendanceComplete(result.modules());
        List<DatasetAnswerInput> datasets = new ArrayList<>();
        for (PersonBusinessQueryService.ModuleResult module : result.modules()) {
            DatasetPlan plan = byType.get(module.type());
            if (plan == null || !plan.userRequested()) {
                continue;
            }
            Map<String, Object> display = switch (module.type()) {
                case TRAVEL -> travelFacts(result.travelSummary());
                case REIMBURSEMENT -> reimbursementFacts(result.reimbursementSummary());
                case PUNCH -> Map.of("attendanceRows", attendanceRows(result.attendance()));
                default -> Map.of();
            };
            boolean complete = requestedDatasetComplete(
                    module.type(), result, module.complete(), attendanceComplete
            );
            datasets.add(new DatasetAnswerInput(
                    plan.datasetCode(), module.type().name(),
                    datasetStatus(module.status()), complete, display, Map.of(),
                    complete ? "已完成" : "数据不完整"
            ));
        }
        return List.copyOf(datasets);
    }

    private boolean requestedDatasetComplete(
            DatasetType type,
            PersonBusinessQueryService.Result result,
            boolean moduleComplete,
            boolean attendanceComplete) {
        if (!moduleComplete) {
            return false;
        }
        return switch (type) {
            case TRAVEL -> result.travelSummary() != null
                    && result.travelSummary().tripCount() != null
                    && result.travelSummary().tripCount().complete()
                    && result.travelSummary().totalAmount() != null
                    && result.travelSummary().totalAmount().complete();
            case REIMBURSEMENT -> result.reimbursementSummary() != null
                    && result.reimbursementSummary().requestedAmount() != null
                    && result.reimbursementSummary().requestedAmount().complete()
                    && result.reimbursementSummary().approvedAmount() != null
                    && result.reimbursementSummary().approvedAmount().complete()
                    && result.reimbursementSummary().paidAmount() != null
                    && result.reimbursementSummary().paidAmount().complete();
            case PUNCH -> attendanceComplete;
            default -> false;
        };
    }

    private boolean attendanceComplete(List<PersonBusinessQueryService.ModuleResult> modules) {
        Set<DatasetType> completeTypes = new HashSet<>();
        for (PersonBusinessQueryService.ModuleResult module : modules) {
            if (module.complete()) {
                completeTypes.add(module.type());
            }
        }
        return completeTypes.containsAll(Set.of(
                DatasetType.TRAVEL, DatasetType.PUNCH, DatasetType.LEAVE,
                DatasetType.SCHEDULE, DatasetType.CALENDAR
        ));
    }

    private Map<String, Object> travelFacts(PersonBusinessQueryService.TravelSummary summary) {
        Map<String, Object> values = new LinkedHashMap<>();
        putMetric(values, "tripCount", summary.tripCount());
        putMetric(values, "totalAmount", summary.totalAmount());
        return Map.copyOf(values);
    }

    private Map<String, Object> reimbursementFacts(PersonBusinessQueryService.ReimbursementSummary summary) {
        Map<String, Object> values = new LinkedHashMap<>();
        putMetric(values, "requestedAmount", summary.requestedAmount());
        putMetric(values, "approvedAmount", summary.approvedAmount());
        putMetric(values, "paidAmount", summary.paidAmount());
        return Map.copyOf(values);
    }

    private void putMetric(
            Map<String, Object> target,
            String code,
            PersonBusinessQueryService.Metric<?> metric) {
        if (metric != null && metric.complete()) {
            target.put(code, metric.value());
        }
    }

    private List<Map<String, Object>> attendanceRows(List<AttendanceDayResult> attendance) {
        return attendance.stream().map(day -> Map.<String, Object>of(
                "date", day.date().toString(),
                "rawPunchStatus", day.rawPunchStatus(),
                "exemptionType", day.exemptionType(),
                "determination", day.determination()
        )).toList();
    }

    private List<MetricDefinition> personMetrics(List<DatasetPlan> plans) {
        Map<DatasetType, String> codes = new EnumMap<>(DatasetType.class);
        plans.stream().filter(DatasetPlan::userRequested)
                .forEach(plan -> codes.put(plan.type(), plan.datasetCode()));
        List<MetricDefinition> metrics = new ArrayList<>();
        if (codes.containsKey(DatasetType.TRAVEL)) {
            metrics.add(metric(codes.get(DatasetType.TRAVEL), "tripCount", "出差次数", ValueType.NUMBER, "次"));
            metrics.add(metric(codes.get(DatasetType.TRAVEL), "totalAmount", "出差总金额", ValueType.AMOUNT, "元"));
        }
        if (codes.containsKey(DatasetType.REIMBURSEMENT)) {
            metrics.add(metric(codes.get(DatasetType.REIMBURSEMENT),
                    "requestedAmount", "报销申请金额", ValueType.AMOUNT, "元"));
            metrics.add(metric(codes.get(DatasetType.REIMBURSEMENT),
                    "approvedAmount", "报销审批金额", ValueType.AMOUNT, "元"));
            metrics.add(metric(codes.get(DatasetType.REIMBURSEMENT),
                    "paidAmount", "报销支付金额", ValueType.AMOUNT, "元"));
        }
        return List.copyOf(metrics);
    }

    private List<TableDefinition> personTables(List<DatasetPlan> plans) {
        String punchCode = plans.stream()
                .filter(plan -> plan.type() == DatasetType.PUNCH && plan.userRequested())
                .map(DatasetPlan::datasetCode)
                .findFirst().orElse(null);
        if (!StringUtils.hasText(punchCode)) {
            return List.of();
        }
        return List.of(new TableDefinition(
                "attendance_table", "考勤核算", punchCode, "attendanceRows",
                List.of(
                        column("date", "日期"),
                        column("rawPunchStatus", "原始打卡"),
                        column("exemptionType", "豁免类型"),
                        column("determination", "核算结论")
                )
        ));
    }

    private List<MetricDefinition> metricDefinitions(List<DatasetAnswerInput> datasets) {
        List<MetricDefinition> definitions = new ArrayList<>();
        for (DatasetAnswerInput dataset : datasets) {
            dataset.displayFacts().forEach((code, value) -> {
                if (value == null || value instanceof String || value instanceof Number
                        || value instanceof Boolean) {
                    definitions.add(metric(
                            dataset.datasetCode(), code, code,
                            value instanceof BigDecimal ? ValueType.AMOUNT
                                    : value instanceof Number ? ValueType.NUMBER : ValueType.TEXT,
                            ""
                    ));
                }
            });
        }
        return List.copyOf(definitions);
    }

    private List<TableDefinition> tableDefinitions(List<DatasetAnswerInput> datasets) {
        List<TableDefinition> definitions = new ArrayList<>();
        for (DatasetAnswerInput dataset : datasets) {
            dataset.displayFacts().forEach((factCode, value) -> {
                if (!(value instanceof List<?> rows) || rows.isEmpty() || !(rows.get(0) instanceof Map<?, ?> first)) {
                    return;
                }
                List<ColumnDefinition> columns = first.keySet().stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .map(code -> column(code, code))
                        .toList();
                if (!columns.isEmpty()) {
                    definitions.add(new TableDefinition(
                            "table_" + dataset.datasetCode().toLowerCase(Locale.ROOT) + "_" + factCode,
                            dataset.label(), dataset.datasetCode(), factCode, columns
                    ));
                }
            });
        }
        return List.copyOf(definitions);
    }

    private AiResponse response(
            AgentRequest request,
            AgentStreamSession stream,
            String runId,
            ResponseContext context,
            boolean complete,
            List<DatasetAnswerInput> datasets,
            List<MetricDefinition> metrics,
            List<TableDefinition> tables,
            List<org.example.ai.agent.business.panorama.model.ProjectIssueResult> issues,
            ArtifactBlock artifact,
            boolean narrative) {
        return answerComposer.compose(new ComposeCommand(
                stream.getMessageId(), runId, request.getConversationId(), context, complete,
                datasets, metrics, tables, issues, artifact, narrative,
                request.getEffectiveQuestion(), request.getUserId(), request.getModelCode(),
                List.of(), ResponseMeta.empty()
        ));
    }

    private void finish(
            AgentRequest request,
            AgentStreamSession stream,
            AiResponse response,
            boolean narrativeExpected) throws Exception {
        finish(request, stream, response, narrativeExpected, new HashSet<>());
    }

    private void finish(
            AgentRequest request,
            AgentStreamSession stream,
            AiResponse response,
            boolean narrativeExpected,
            Set<String> startedBlockIds) throws Exception {
        boolean hasNarrative = false;
        for (ResponseBlock block : response.blocks()) {
            if (block instanceof TextBlock text) {
                hasNarrative = true;
                stream.startTextResponse(text.id(), text.title(), text.order(), text.source());
                stream.appendTextResponse(text.markdown());
                stream.finishTextResponse(text.markdown());
            } else {
                startBlock(stream, startedBlockIds, block);
                stream.publishResponseBlock(block);
            }
        }
        if (narrativeExpected && !hasNarrative) {
            stream.startTextResponse(
                    "business_narrative", "业务说明", 90, BlockSource.AI
            );
            stream.failResponseBlock(new BlockErrorPayload(
                    "business_narrative", BlockType.TEXT, "MODEL_SUMMARY_FAILED",
                    "业务数据已返回，智能说明暂不可用", true
            ));
        }
        stream.setResponseDataComplete(response.dataComplete());
        stream.beginFinalization();
        AiResponse finalResponse = stream.getChatResponseAccumulator().complete();
        stream.sendResponseSnapshot();
        chatSessionService.saveAssistantMessage(
                request.getUserId(), request.getConversationId(), visibleText(finalResponse),
                response.runId(), request.getModelCode(), "CHAT",
                objectMapper.writeValueAsString(finalResponse)
        );
        stream.finishChatResponse();
    }

    private void startWithContext(AgentStreamSession stream, ResponseContext context) throws Exception {
        stream.startChatResponse();
        stream.getChatResponseAccumulator().setContext(context);
        stream.sendResponseSnapshot();
    }

    private void startBlock(
            AgentStreamSession stream,
            Set<String> startedBlockIds,
            ResponseBlock block) throws Exception {
        if (startedBlockIds.add(block.id())) {
            stream.sendResponseEvent(stream.getResponseEventFactory().blockStart(block));
        }
    }

    private void saveState(
            AgentRequest request,
            String runId,
            SubjectCandidate subject,
            BusinessQueryIntent intent,
            String panoramaSnapshotId,
            List<String> semanticCodes) {
        BusinessConversationState state = new BusinessConversationState();
        state.setRouteType("BUSINESS_ASSISTANT");
        state.setBusinessTopic(request.getUserQuestion());
        state.setActiveObjectType(subject.type().name());
        state.setActiveObjectIds(List.of(subjectId(subject)));
        state.setLastRunId(runId);
        Map<String, Object> input = new LinkedHashMap<>(canonicalQuery(intent));
        input.put("selectionToken", subject.selectionToken());
        input.put("subjectType", subject.type().name());
        if (subject.type() == BusinessSubjectType.PERSON
                || subject.type() == BusinessSubjectType.DEPARTMENT) {
            input.put("datasetCodes", List.copyOf(semanticCodes));
        }
        if (StringUtils.hasText(panoramaSnapshotId)) {
            input.put("panoramaSnapshotId", panoramaSnapshotId);
        }
        state.setLastInput(input);
        state.setUpdatedAt(LocalDateTime.now());
        try {
            conversationStateService.saveState(
                    request.getUserId(), request.getConversationId(), state
            );
        } catch (RuntimeException exception) {
            // 会话状态失败不能破坏已成功取得的业务结果。
            log.warn("业务助手会话状态保存失败，runId={}，errorType={}",
                    runId, exception.getClass().getSimpleName());
        }
    }

    /**
     * 候选轮保存已确认的主体类型和规范业务语义，客户端仍只能回传并复核不透明选择令牌。
     */
    private void saveCandidateState(
            AgentRequest request,
            String runId,
            BusinessQueryIntent intent,
            Selection personSelection,
            List<SubjectCandidate> candidates) {
        if (intent.subjectType() == null) {
            return;
        }
        BusinessConversationState state = new BusinessConversationState();
        state.setRouteType("BUSINESS_ASSISTANT");
        state.setBusinessTopic(request.getUserQuestion());
        state.setActiveObjectType(intent.subjectType().name());
        state.setActiveObjectIds(candidates.stream().map(this::subjectId).limit(100).toList());
        state.setAwaitingClarification(true);
        state.setLastRunId(runId);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("subjectType", intent.subjectType().name());
        if (personSelection != null) {
            input.put("datasetCodes", personSelection.semanticCodes());
        }
        state.setLastInput(input);
        state.setUpdatedAt(LocalDateTime.now());
        try {
            conversationStateService.saveState(
                    request.getUserId(), request.getConversationId(), state
            );
        } catch (RuntimeException exception) {
            log.warn("业务候选会话状态保存失败，runId={}，errorType={}",
                    runId, exception.getClass().getSimpleName());
        }
    }

    private SubjectResolutionRequest subjectRequest(
            AgentRequest request,
            BusinessQueryIntent intent,
            String runId) {
        String selectionToken = selectionToken(request, intent);
        BusinessSubjectType subjectType = intent.subjectType();
        boolean selected = StringUtils.hasText(selectionToken);
        boolean myProjects = !selected && subjectType == BusinessSubjectType.PROJECT
                && !StringUtils.hasText(intent.projectCode())
                && isMyProjectsRequest(request.getEffectiveQuestion());
        boolean managerSearch = !selected && subjectType == BusinessSubjectType.PROJECT
                && request.getEffectiveQuestion().contains("项目经理")
                && StringUtils.hasText(intent.personName());
        String name = selected || myProjects || managerSearch || StringUtils.hasText(intent.projectCode())
                ? null : intent.personName();
        return new SubjectResolutionRequest(
                runId, request.getUserId(), request.getConversationId(), request.getAuthorization(), Map.of(),
                subjectType, selectionToken,
                selected ? null : intent.projectCode(), name,
                managerSearch ? intent.personName() : null,
                selected ? null : intent.projectYear(), myProjects,
                selected ? null : intent.employeeNo(), pageNumber(request), pageSize(request)
        );
    }

    private int pageNumber(AgentRequest request) {
        return boundedNumber(request.getExtra(), "pageNumber", 1, 1, Integer.MAX_VALUE);
    }

    private int pageSize(AgentRequest request) {
        return boundedNumber(request.getExtra(), "pageSize", DEFAULT_PAGE_SIZE, 1, 200);
    }

    private int boundedNumber(
            Map<String, Object> values,
            String key,
            int defaultValue,
            int minimum,
            int maximum) {
        Object value = values == null ? null : values.get(key);
        if (!(value instanceof Number number)) {
            return defaultValue;
        }
        long candidate = number.longValue();
        return candidate < minimum || candidate > maximum ? defaultValue : (int) candidate;
    }

    private String selectionToken(AgentRequest request, BusinessQueryIntent intent) {
        Object selected = request.getExtra() == null ? null : request.getExtra().get("selectionToken");
        if (selected instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        // 显式项目编码代表新查询，禁止旧会话选择令牌把主体重新指回上一个项目。
        if (intent.subjectType() == BusinessSubjectType.PROJECT
                && StringUtils.hasText(intent.projectCode())) {
            return null;
        }
        return trustedInheritedText(request, "selectionToken");
    }

    private String trustedInheritedText(AgentRequest request, String key) {
        Object trusted = request.getInheritedInput() == null ? null : request.getInheritedInput().get(key);
        if (trusted instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return null;
    }

    private BusinessSubjectType resolveSubjectType(BusinessQueryIntent intent, AgentRequest request) {
        if (intent.subjectType() != null) {
            return intent.subjectType();
        }
        String inherited = trustedInheritedText(request, "subjectType");
        if (!StringUtils.hasText(inherited)) {
            return null;
        }
        try {
            return BusinessSubjectType.valueOf(inherited.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private BusinessQueryIntent withSubjectType(
            BusinessQueryIntent intent,
            BusinessSubjectType subjectType) {
        return new BusinessQueryIntent(
                subjectType, intent.projectCode(), intent.personName(), intent.employeeNo(),
                intent.projectYear(), intent.periodStart(), intent.periodEnd(), intent.datasetCodes(),
                intent.refresh(), intent.exportFormat(), intent.anomalyPeopleRequested()
        );
    }

    private boolean isMyProjectsRequest(String question) {
        return StringUtils.hasText(question) && List.of(
                "我的项目", "我负责的项目", "我管理的项目", "有权查看的项目", "全部项目"
        ).stream().anyMatch(question::contains);
    }

    private ModelCallContext modelContext(AgentRequest request, String runId) {
        return ModelCallContext.builder()
                .runId(runId)
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                .modelCode(request.getModelCode())
                .callType(ModelCallType.PLANNER)
                .build();
    }

    private ResponseContext context(BusinessQueryIntent intent, SubjectCandidate subject) {
        return new ResponseContext(
                subject.type().name(), subjectId(subject), subject.displayName(), scopeLabel(intent),
                intent.periodStart(), intent.periodEnd(), LocalDateTime.now()
        );
    }

    private String subjectId(SubjectCandidate subject) {
        if (subject.type() == BusinessSubjectType.PROJECT) {
            return subject.projectCode();
        }
        return StringUtils.hasText(subject.maskedEmployeeNo())
                ? subject.maskedEmployeeNo() : subject.displayName();
    }

    private String scopeLabel(BusinessQueryIntent intent) {
        if (intent.projectYear() != null) {
            return intent.projectYear() + "年度";
        }
        if (intent.periodStart() != null && intent.periodEnd() != null) {
            return intent.periodStart() + " 至 " + intent.periodEnd();
        }
        return "当前范围";
    }

    private Map<String, Object> canonicalQuery(BusinessQueryIntent intent) {
        Map<String, Object> query = new LinkedHashMap<>();
        if (intent.projectYear() != null) {
            query.put("projectYear", intent.projectYear());
        }
        if (intent.periodStart() != null) {
            query.put("startDate", intent.periodStart().toString());
        }
        if (intent.periodEnd() != null) {
            query.put("endDate", intent.periodEnd().toString());
        }
        return Map.copyOf(query);
    }

    /**
     * 只继承服务端会话状态中的时间条件，客户端 extra 和 pageContext 不参与权限或查询范围继承。
     */
    private BusinessQueryIntent inheritQueryContext(
            BusinessQueryIntent intent,
            AgentRequest request) {
        if (request.isContextReset() || request.getInheritedInput() == null
                || request.getInheritedInput().isEmpty()) {
            return intent;
        }
        Map<String, Object> inherited = request.getInheritedInput();
        Integer projectYear = intent.projectYear() != null
                ? intent.projectYear() : integerValue(inherited.get("projectYear"));
        java.time.LocalDate start = intent.periodStart() != null
                ? intent.periodStart() : dateValue(inherited.get("startDate"));
        java.time.LocalDate end = intent.periodEnd() != null
                ? intent.periodEnd() : dateValue(inherited.get("endDate"));
        return new BusinessQueryIntent(
                intent.subjectType(), intent.projectCode(), intent.personName(), intent.employeeNo(),
                projectYear, start, end, intent.datasetCodes(), intent.refresh(), intent.exportFormat(),
                intent.anomalyPeopleRequested()
        );
    }

    /**
     * 仅为人员和部门追问继承服务端保存的数据语义，项目数据集语义保持独立。
     */
    private BusinessQueryIntent inheritPersonDatasetContext(
            BusinessQueryIntent intent,
            AgentRequest request,
            BusinessSubjectType subjectType) {
        if (subjectType == BusinessSubjectType.PROJECT
                || request.isContextReset()
                || !intent.datasetCodes().isEmpty()) {
            return intent;
        }
        List<String> inheritedCodes = trustedInheritedStringList(request, "datasetCodes");
        if (inheritedCodes.isEmpty()) {
            return intent;
        }
        return new BusinessQueryIntent(
                intent.subjectType(), intent.projectCode(), intent.personName(), intent.employeeNo(),
                intent.projectYear(), intent.periodStart(), intent.periodEnd(), inheritedCodes,
                intent.refresh(), intent.exportFormat(), intent.anomalyPeopleRequested()
        );
    }

    private List<String> trustedInheritedStringList(AgentRequest request, String key) {
        Object value = request.getInheritedInput() == null
                ? null : request.getInheritedInput().get(key);
        if (!(value instanceof List<?> values) || values.isEmpty()
                || values.stream().anyMatch(item -> !(item instanceof String))) {
            return List.of();
        }
        return values.stream().map(String.class::cast).toList();
    }

    private Integer integerValue(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private java.time.LocalDate dateValue(Object value) {
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(text.trim());
        } catch (java.time.DateTimeException ignored) {
            return null;
        }
    }

    private Map<String, String> datasetNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (ReportDataset dataset : safeList(reportDatasetService.list())) {
            if (dataset != null && Boolean.TRUE.equals(dataset.getEnabled())
                    && StringUtils.hasText(dataset.getDatasetCode())) {
                names.put(dataset.getDatasetCode(), StringUtils.hasText(dataset.getDatasetName())
                        ? dataset.getDatasetName() : dataset.getDatasetCode());
            }
        }
        return Map.copyOf(names);
    }

    private List<Map<String, Object>> candidateRows(List<SubjectCandidate> candidates) {
        return candidates.stream().map(candidate -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("displayName", candidate.displayName());
            row.put("projectCode", Objects.toString(candidate.projectCode(), ""));
            row.put("projectType", Objects.toString(candidate.projectType(), ""));
            row.put("maskedEmployeeNo", Objects.toString(candidate.maskedEmployeeNo(), ""));
            row.put("selectionToken", candidate.selectionToken());
            return Map.copyOf(row);
        }).toList();
    }

    private String safeMessage(DatasetExecutionStatus status, DatasetExecutionResult result) {
        if (status == DatasetExecutionStatus.DENIED) {
            return "因权限不足未返回数据";
        }
        if (result != null && StringUtils.hasText(result.safeMessage())) {
            return result.safeMessage();
        }
        return switch (status) {
            case SUCCESS -> "查询完成";
            case EMPTY -> "未查询到数据";
            case TIMEOUT -> "查询超时";
            case RUNNING -> "正在查询";
            default -> "查询失败";
        };
    }

    private DatasetExecutionStatus datasetStatus(PersonBusinessQueryService.ModuleStatus status) {
        return switch (status) {
            case REUSED, SUCCESS -> DatasetExecutionStatus.SUCCESS;
            case EMPTY -> DatasetExecutionStatus.EMPTY;
            case DENIED -> DatasetExecutionStatus.DENIED;
            case TIMEOUT -> DatasetExecutionStatus.TIMEOUT;
            case FAILED -> DatasetExecutionStatus.FAILED;
        };
    }

    private String personFactCode(DatasetType type) {
        return switch (type) {
            case TRAVEL -> PersonBusinessQueryService.TRAVEL_RECORDS;
            case PUNCH -> PersonBusinessQueryService.PUNCH_RECORDS;
            case LEAVE -> PersonBusinessQueryService.LEAVE_RECORDS;
            case SCHEDULE -> PersonBusinessQueryService.SCHEDULE_RECORDS;
            case CALENDAR -> PersonBusinessQueryService.CALENDAR_RECORDS;
            case REIMBURSEMENT -> PersonBusinessQueryService.REIMBURSEMENT_RECORDS;
        };
    }

    private DatasetAnswerInput failedDataset(String code, String message) {
        return new DatasetAnswerInput(
                code, code, DatasetExecutionStatus.FAILED, false,
                Map.of(), Map.of(), message
        );
    }

    private MetricDefinition metric(
            String datasetCode,
            String code,
            String label,
            ValueType type,
            String unit) {
        return new MetricDefinition(datasetCode, code, label, type, unit, Tone.DEFAULT);
    }

    private ColumnDefinition column(String code, String label) {
        return new ColumnDefinition(code, label, ValueType.TEXT, "", Tone.DEFAULT);
    }

    private String visibleText(AiResponse response) {
        if (response == null) {
            return "业务查询结果";
        }
        return response.blocks().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::markdown)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("业务查询结果");
    }

    private void validate(AgentRequest request, AgentStreamSession stream, String runId) {
        if (request == null || stream == null || !StringUtils.hasText(runId)
                || !StringUtils.hasText(request.getUserId())
                || !StringUtils.hasText(request.getConversationId())
                || !StringUtils.hasText(request.getAuthorization())
                || !StringUtils.hasText(request.getEffectiveQuestion())) {
            throw new IllegalArgumentException("业务助手请求不完整");
        }
    }

    private <T> List<T> safeList(List<T> source) {
        return source == null ? List.of() : source;
    }

    /**
     * 项目全景进度只维护通用状态区块，不暴露任何业务事实。
     */
    private final class StatusProgress {
        private final AgentStreamSession stream;
        private final Map<String, String> names;
        private final Map<String, DatasetExecutionStatus> states = new LinkedHashMap<>();

        private StatusProgress(
                AgentStreamSession stream,
                Map<String, String> names) {
            this.stream = stream;
            this.names = names;
        }

        private Map<String, String> names() {
            return names;
        }

        private void accept(ProjectPanoramaProgressEvent event) {
            states.put(event.datasetCode(), event.status());
            try {
                StatusListBlock current = block();
                stream.sendResponseEvent(stream.getResponseEventFactory().blockStart(current));
                stream.publishResponseBlock(current);
            } catch (Exception exception) {
                throw new IllegalStateException("发送业务数据集进度失败", exception);
            }
        }

        private StatusListBlock block() {
            List<StatusListBlock.StatusItem> items = states.entrySet().stream()
                    .map(entry -> new StatusListBlock.StatusItem(
                            entry.getKey(), names.getOrDefault(entry.getKey(), entry.getKey()),
                            entry.getValue().name(), tone(entry.getValue()), safeMessage(entry.getValue(), null)
                    )).toList();
            return new StatusListBlock(
                    STATUS_BLOCK_ID, "数据状态", 10,
                    BlockStatus.READY, BlockSource.BUSINESS, items
            );
        }
    }

    private Tone tone(DatasetExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> Tone.SUCCESS;
            case EMPTY -> Tone.MUTED;
            case PENDING, RUNNING -> Tone.INFO;
            case DENIED, FAILED, TIMEOUT -> Tone.DANGER;
        };
    }
}
