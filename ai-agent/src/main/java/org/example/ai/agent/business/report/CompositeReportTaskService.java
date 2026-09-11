package org.example.ai.agent.business.report;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.report.BusinessReportPlanService.LogicalReportPlan;
import org.example.ai.agent.business.report.BusinessReportPlanService.LogicalReportSection;
import org.example.ai.agent.business.report.BusinessReportPlanService.PlannedReport;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportSectionMapper;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 组合报告任务的创建、幂等校验和数据库租约边界。
 */
@Service
public class CompositeReportTaskService {

    private static final Set<String> FORMATS = Set.of("XLSX", "DOCX", "PDF");
    private static final Set<String> SECTION_STATUSES = Set.of(
            "REUSED", "QUERIED", "EMPTY", "DENIED", "FAILED"
    );
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int MAX_SECTIONS = 64;

    private final CompositeReportTaskMapper taskMapper;
    private final CompositeReportSectionMapper sectionMapper;
    private final Clock clock;

    @Autowired
    public CompositeReportTaskService(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper) {
        this(taskMapper, sectionMapper, Clock.systemDefaultZone());
    }

    /** 测试构造器允许固定任务时间边界。 */
    CompositeReportTaskService(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper,
            Clock clock) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper不能为空");
        this.sectionMapper = Objects.requireNonNull(sectionMapper, "sectionMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /**
     * 主任务和全部章节在同一事务中写入；唯一键竞争时只复用fingerprint一致的任务。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompositeReportTask create(CreateCommand command) {
        ValidatedCreate validated = validateCreate(command);
        CompositeReportTask existing = taskMapper.selectByRequestKey(validated.requestKey());
        if (existing != null) {
            return verifySameRequest(existing, validated.requestFingerprint());
        }
        CompositeReportTask task = newTask(validated);
        try {
            if (taskMapper.insertTask(task) != 1) {
                throw new IllegalStateException("组合报告任务写入失败");
            }
        } catch (DuplicateKeyException exception) {
            CompositeReportTask concurrent = taskMapper.selectByRequestKey(validated.requestKey());
            if (concurrent == null) {
                throw new IllegalStateException("组合报告幂等任务竞争后不可见", exception);
            }
            return verifySameRequest(concurrent, validated.requestFingerprint());
        }
        insertSections(task.getTaskId(), validated.plan().sections(), validated.now());
        return task;
    }

    /**
     * 先恢复崩溃worker的过期运行态，再以条件UPDATE竞争PENDING/RETRY候选。
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<CompositeReportTask> claimNext(
            String workerId,
            int leaseSeconds,
            int scanLimit) {
        requireText(workerId, 128, "workerId");
        requireRange(leaseSeconds, 1, 3600, "leaseSeconds");
        requireRange(scanLimit, 1, 100, "scanLimit");
        taskMapper.recoverExpiredLeases();
        List<String> candidates = taskMapper.selectClaimCandidateIds(scanLimit);
        if (candidates == null) {
            return Optional.empty();
        }
        for (String taskId : candidates) {
            if (StringUtils.hasText(taskId)
                    && taskMapper.tryClaim(taskId, workerId, leaseSeconds) == 1) {
                CompositeReportTask claimed = taskMapper.selectByTaskId(taskId);
                if (claimed == null) {
                    throw new IllegalStateException("已领取的组合报告任务不可见");
                }
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    public boolean renewLease(String taskId, String workerId, int leaseSeconds) {
        requireTaskAndWorker(taskId, workerId);
        requireRange(leaseSeconds, 1, 3600, "leaseSeconds");
        return taskMapper.renewLease(taskId, workerId, leaseSeconds) == 1;
    }

    public List<CompositeReportSection> sections(String taskId) {
        requireText(taskId, 32, "taskId");
        List<CompositeReportSection> sections = sectionMapper.selectByTaskId(taskId);
        return sections == null ? List.of() : List.copyOf(sections);
    }

    public boolean complete(
            CompositeReportTask task,
            String workerId,
            ArtifactMetadata artifact) {
        if (task == null) {
            throw new IllegalArgumentException("task不能为空");
        }
        requireTaskAndWorker(task.getTaskId(), workerId);
        validateArtifact(artifact);
        return taskMapper.completeWithArtifact(
                task.getTaskId(), workerId, Boolean.TRUE.equals(task.getDataComplete()),
                artifact.storagePath(), artifact.fileName(), artifact.mimeType(),
                artifact.fileSize(), artifact.checksum()
        ) == 1;
    }

    public boolean retry(
            String taskId,
            String workerId,
            String safeErrorCode,
            String safeErrorMessage,
            int retryDelaySeconds) {
        requireTaskAndWorker(taskId, workerId);
        requireCode(safeErrorCode, "safeErrorCode");
        requireText(safeErrorMessage, 1000, "safeErrorMessage");
        requireRange(retryDelaySeconds, 0, 3600, "retryDelaySeconds");
        return taskMapper.markRetry(
                taskId, workerId, safeErrorCode, safeErrorMessage, retryDelaySeconds
        ) == 1;
    }

    /** 配置版本失配等失败关闭场景直接进入终态，禁止继续渲染或重试。 */
    public boolean fail(
            String taskId,
            String workerId,
            String safeErrorCode,
            String safeErrorMessage) {
        requireTaskAndWorker(taskId, workerId);
        requireCode(safeErrorCode, "safeErrorCode");
        requireText(safeErrorMessage, 1000, "safeErrorMessage");
        return taskMapper.markFailed(
                taskId, workerId, safeErrorCode, safeErrorMessage
        ) == 1;
    }

    private ValidatedCreate validateCreate(CreateCommand command) {
        if (command == null || command.plannedReport() == null
                || command.plannedReport().plan() == null
                || command.plannedReport().plan().subjectType() == null) {
            throw new IllegalArgumentException("组合报告创建命令不完整");
        }
        requireText(command.userId(), 128, "userId");
        requireText(command.sessionId(), 64, "sessionId");
        if (StringUtils.hasText(command.authorization())) {
            requireText(command.authorization(), 4096, "authorization");
        }
        LogicalReportPlan plan = command.plannedReport().plan();
        requireCode(plan.templateCode(), "templateCode");
        requireText(plan.subjectId(), 128, "subjectId");
        if (!FORMATS.contains(plan.format())) {
            throw new IllegalArgumentException("format仅支持XLSX、DOCX或PDF，且必须保持规范大写");
        }
        requireChecksum(command.plannedReport().templateChecksum(), "templateChecksum");
        if (plan.sections() == null || plan.sections().isEmpty()
                || plan.sections().size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("报告章节数量不合法");
        }
        validateSections(plan.subjectType(), plan.sections());
        boolean structurallyComplete = plan.sections().stream()
                .allMatch(section -> Set.of("REUSED", "QUERIED", "EMPTY")
                        .contains(section.status()));
        boolean hasUnavailableDisclosure = plan.sections().stream()
                .anyMatch(section -> BusinessAssistantReportService
                        .isPersonUnavailableMessage(section.safeMessage()));
        if (plan.dataComplete() && (!structurallyComplete || hasUnavailableDisclosure)) {
            throw new IllegalArgumentException("dataComplete与章节状态或缺失说明不一致");
        }
        Object safeQuery = ReportDatasetValidator.freezeSafeValue(command.canonicalQuery());
        if (!(safeQuery instanceof Map<?, ?> queryMap)) {
            throw new IllegalArgumentException("规范查询必须是Map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> canonicalQuery = (Map<String, Object>) queryMap;
        LocalDateTime now = LocalDateTime.now(clock);
        if (command.expiresAt() == null || !command.expiresAt().isAfter(now)) {
            throw new IllegalArgumentException("expiresAt必须晚于当前时间");
        }
        requireRange(command.maxAttempts(), 1, 10, "maxAttempts");
        String requestKey = hash(requestKeyMaterial(command, canonicalQuery));
        String fingerprint = hash(fingerprintMaterial(command, canonicalQuery));
        return new ValidatedCreate(
                command.userId(), command.sessionId(), plan,
                command.plannedReport().templateChecksum(),
                command.expiresAt(), command.maxAttempts(), requestKey, fingerprint, now
        );
    }

    private void validateSections(
            BusinessSubjectType subjectType,
            List<LogicalReportSection> sections) {
        Set<String> datasets = new java.util.HashSet<>();
        int firstResolvedIndex = -1;
        for (int index = 0; index < sections.size(); index++) {
            LogicalReportSection section = sections.get(index);
            if (section == null) {
                throw new IllegalArgumentException("报告章节不能为空");
            }
            requireCode(section.datasetCode(), "datasetCode");
            requireChecksum(section.fieldPolicyChecksum(), "fieldPolicyChecksum");
            if (!datasets.add(section.datasetCode())
                    || !SECTION_STATUSES.contains(section.status())) {
                throw new IllegalArgumentException("报告章节重复或状态不合法");
            }
            boolean resolved = Set.of("REUSED", "QUERIED", "EMPTY")
                    .contains(section.status());
            if (resolved && firstResolvedIndex < 0) {
                firstResolvedIndex = index;
            }
            if (resolved != StringUtils.hasText(section.snapshotId())) {
                throw new IllegalArgumentException("报告章节状态与快照引用不一致");
            }
            String expectedMessage = switch (section.status()) {
                case "DENIED" -> BusinessReportPlanService.DENIED_MESSAGE;
                case "FAILED" -> BusinessReportPlanService.FAILED_MESSAGE;
                default -> null;
            };
            boolean safePersonDisclosure = resolved
                    && subjectType == BusinessSubjectType.PERSON
                    && index == firstResolvedIndex
                    && BusinessAssistantReportService.isPersonUnavailableMessage(section.safeMessage());
            if (!Objects.equals(expectedMessage, section.safeMessage()) && !safePersonDisclosure) {
                throw new IllegalArgumentException("章节状态与安全说明不一致");
            }
        }
    }

    private Map<String, Object> requestKeyMaterial(
            CreateCommand command,
            Map<String, Object> query) {
        LogicalReportPlan plan = command.plannedReport().plan();
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("userId", command.userId());
        material.put("sessionId", command.sessionId());
        material.put("subjectType", plan.subjectType().name());
        material.put("subjectId", plan.subjectId());
        material.put("templateCode", plan.templateCode());
        material.put("templateChecksum", command.plannedReport().templateChecksum());
        material.put("format", plan.format());
        material.put("query", query);
        material.put("snapshotReferences", plan.sections().stream()
                .map(section -> Map.of(
                        "datasetCode", section.datasetCode(),
                        "snapshotId", Objects.toString(section.snapshotId(), ""),
                        "fieldPolicyChecksum", section.fieldPolicyChecksum(),
                        "safeMessage", Objects.toString(section.safeMessage(), "")
                ))
                .toList());
        return material;
    }

    private Map<String, Object> fingerprintMaterial(
            CreateCommand command,
            Map<String, Object> query) {
        Map<String, Object> material = new LinkedHashMap<>(requestKeyMaterial(command, query));
        material.put("dataComplete", command.plannedReport().plan().dataComplete());
        material.put("sections", command.plannedReport().plan().sections().stream()
                .map(section -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("datasetCode", section.datasetCode());
                    value.put("snapshotId", section.snapshotId());
                    value.put("fieldPolicyChecksum", section.fieldPolicyChecksum());
                    value.put("status", section.status());
                    value.put("safeMessage", section.safeMessage());
                    return value;
                }).toList());
        return material;
    }

    private String hash(Object material) {
        Object safe = ReportDatasetValidator.freezeSafeValue(material);
        return ContentHashUtils.sha256(ReportDatasetValidator.canonicalSafeValue(safe));
    }

    private CompositeReportTask newTask(ValidatedCreate command) {
        LogicalReportPlan plan = command.plan();
        CompositeReportTask task = new CompositeReportTask();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setRequestKey(command.requestKey());
        task.setRequestFingerprint(command.requestFingerprint());
        task.setSessionId(command.sessionId());
        task.setUserId(command.userId());
        task.setSubjectType(plan.subjectType().name());
        task.setSubjectId(plan.subjectId());
        task.setTemplateCode(plan.templateCode());
        task.setTemplateChecksum(command.templateChecksum());
        task.setFormat(plan.format());
        task.setStatus("PENDING");
        task.setDataComplete(plan.dataComplete());
        task.setAttemptCount(0);
        task.setMaxAttempts(command.maxAttempts());
        task.setExpiresAt(command.expiresAt());
        task.setCreatedAt(command.now());
        task.setUpdatedAt(command.now());
        return task;
    }

    private void insertSections(
            String taskId,
            List<LogicalReportSection> source,
            LocalDateTime now) {
        for (int index = 0; index < source.size(); index++) {
            LogicalReportSection logical = source.get(index);
            CompositeReportSection section = new CompositeReportSection();
            section.setTaskId(taskId);
            section.setDatasetCode(logical.datasetCode());
            section.setSnapshotId(logical.snapshotId());
            section.setFieldPolicyChecksum(logical.fieldPolicyChecksum());
            section.setStatus(logical.status());
            section.setDisplayOrder(index + 1);
            section.setSafeMessage(logical.safeMessage());
            section.setCreatedAt(now);
            section.setUpdatedAt(now);
            if (sectionMapper.insertSection(section) != 1) {
                throw new IllegalStateException("组合报告章节写入失败");
            }
        }
    }

    private CompositeReportTask verifySameRequest(
            CompositeReportTask existing,
            String expectedFingerprint) {
        if (!Objects.equals(existing.getRequestFingerprint(), expectedFingerprint)) {
            throw new IllegalStateException("组合报告幂等键冲突：同一requestKey对应不同请求");
        }
        return existing;
    }

    private void validateArtifact(ArtifactMetadata artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact不能为空");
        }
        requireText(artifact.storagePath(), 1000, "storagePath");
        if (artifact.storagePath().startsWith("/") || artifact.storagePath().startsWith("\\")
                || artifact.storagePath().contains("..")) {
            throw new IllegalArgumentException("storagePath必须是受控相对路径");
        }
        requireText(artifact.fileName(), 255, "fileName");
        requireText(artifact.mimeType(), 128, "mimeType");
        if (artifact.fileSize() < 0) {
            throw new IllegalArgumentException("fileSize不能为负数");
        }
        requireChecksum(artifact.checksum(), "checksum");
    }

    private void requireTaskAndWorker(String taskId, String workerId) {
        requireText(taskId, 32, "taskId");
        requireText(workerId, 128, "workerId");
    }

    private void requireCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }

    private void requireChecksum(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + "必须是64位SHA-256十六进制");
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || value.length() > maxLength
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }

    private void requireRange(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + "必须在" + minimum + "至" + maximum + "之间");
        }
    }

    public record CreateCommand(
            String userId,
            String sessionId,
            String authorization,
            PlannedReport plannedReport,
            Map<String, Object> canonicalQuery,
            LocalDateTime expiresAt,
            int maxAttempts) {

        public CreateCommand {
            canonicalQuery = canonicalQuery == null ? Map.of() : canonicalQuery;
        }

        /** 日志不输出认证、主体和查询内容。 */
        @Override
        public String toString() {
            LogicalReportPlan plan = plannedReport == null ? null : plannedReport.plan();
            return "CreateCommand[format=" + (plan == null ? null : plan.format())
                    + ", sectionCount=" + (plan == null || plan.sections() == null
                    ? 0 : plan.sections().size())
                    + ", authorizationPresent=" + StringUtils.hasText(authorization) + ']';
        }
    }

    public record ArtifactMetadata(
            String storagePath,
            String fileName,
            String mimeType,
            long fileSize,
            String checksum) {
    }

    private record ValidatedCreate(
            String userId,
            String sessionId,
            LogicalReportPlan plan,
            String templateChecksum,
            LocalDateTime expiresAt,
            int maxAttempts,
            String requestKey,
            String requestFingerprint,
            LocalDateTime now) {
    }
}
