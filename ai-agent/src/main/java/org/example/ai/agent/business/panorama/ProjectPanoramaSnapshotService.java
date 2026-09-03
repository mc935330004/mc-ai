package org.example.ai.agent.business.panorama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshot;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotModuleMapper;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 持久化一次项目全景执行的稳定聚合锚点。
 */
@Service
public class ProjectPanoramaSnapshotService {

    private static final int MAX_MODULES = 32;
    private static final Set<DatasetExecutionStatus> TERMINAL_STATUSES = Set.of(
            DatasetExecutionStatus.SUCCESS,
            DatasetExecutionStatus.EMPTY,
            DatasetExecutionStatus.DENIED,
            DatasetExecutionStatus.FAILED,
            DatasetExecutionStatus.TIMEOUT
    );

    private final ProjectPanoramaSnapshotMapper snapshotMapper;
    private final ProjectPanoramaSnapshotModuleMapper moduleMapper;
    private final BusinessSnapshotMapper businessSnapshotMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ProjectPanoramaSnapshotService(
            ProjectPanoramaSnapshotMapper snapshotMapper,
            ProjectPanoramaSnapshotModuleMapper moduleMapper,
            BusinessSnapshotMapper businessSnapshotMapper,
            ObjectMapper objectMapper) {
        this(snapshotMapper, moduleMapper, businessSnapshotMapper, objectMapper, Clock.systemDefaultZone());
    }

    ProjectPanoramaSnapshotService(
            ProjectPanoramaSnapshotMapper snapshotMapper,
            ProjectPanoramaSnapshotModuleMapper moduleMapper,
            BusinessSnapshotMapper businessSnapshotMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.moduleMapper = Objects.requireNonNull(moduleMapper, "moduleMapper不能为空");
        this.businessSnapshotMapper = Objects.requireNonNull(
                businessSnapshotMapper,
                "businessSnapshotMapper不能为空"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /**
     * 聚合记录和模块引用在同一事务内写入，任何一行失败都会整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public SnapshotReference create(CreateCommand command) {
        ValidatedCommand validated = validate(command);
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, BusinessSnapshot> snapshots = loadReferencedSnapshots(
                validated,
                now
        );
        LocalDateTime expiresAt = calculateExpiry(snapshots.values(), now);
        ProjectPanoramaSnapshot aggregate = aggregate(validated, expiresAt, now);
        if (snapshotMapper.insert(aggregate) != 1) {
            throw new IllegalStateException("项目全景聚合快照写入失败");
        }
        for (int index = 0; index < validated.modules().size(); index++) {
            ProjectPanoramaSnapshotModule reference = reference(
                    aggregate.getPanoramaSnapshotId(),
                    validated.modules().get(index),
                    index,
                    now
            );
            if (moduleMapper.insert(reference) != 1) {
                throw new IllegalStateException("项目全景模块快照引用写入失败");
            }
        }
        return new SnapshotReference(
                aggregate.getPanoramaSnapshotId(),
                aggregate.getStatus(),
                aggregate.getExpiresAt()
        );
    }

    private ValidatedCommand validate(CreateCommand command) {
        if (command == null || command.subject() == null || command.plan() == null) {
            throw new IllegalArgumentException("项目全景聚合快照命令不完整");
        }
        requireText(command.userId(), 128, "userId");
        requireText(command.sessionId(), 64, "sessionId");
        requireText(command.subject().projectId(), 128, "projectId");
        requireText(command.subject().projectCode(), 128, "projectCode");
        requireText(command.subject().projectType(), 128, "projectType");
        requireChecksum(command.plan().configChecksum(), "profileChecksum");
        if (command.plan().profileId() == null || command.plan().profileId() <= 0) {
            throw new IllegalArgumentException("profileId不合法");
        }
        if (command.modules().isEmpty() || command.modules().size() > MAX_MODULES
                || command.modules().size() != command.plan().modules().size()) {
            throw new IllegalArgumentException("项目全景模块结果数量不合法");
        }
        for (int index = 0; index < command.modules().size(); index++) {
            ProjectPanoramaResult.ModuleResult result = command.modules().get(index);
            ProjectPanoramaPlan.Module configured = command.plan().modules().get(index);
            if (result == null || !TERMINAL_STATUSES.contains(result.status())
                    || !Objects.equals(result.datasetCode(), configured.datasetCode())
                    || result.required() != configured.required()
                    || (StringUtils.hasText(result.snapshotId())
                    && result.status() != DatasetExecutionStatus.SUCCESS
                    && result.status() != DatasetExecutionStatus.EMPTY)) {
                throw new IllegalArgumentException("项目全景模块结果与执行方案不一致");
            }
        }
        Object safeQuery = ReportDatasetValidator.freezeSafeValue(command.canonicalQuery());
        if (!(safeQuery instanceof Map<?, ?> queryMap)) {
            throw new IllegalArgumentException("项目全景查询条件必须是Map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> canonicalQuery = (Map<String, Object>) queryMap;
        String queryHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(canonicalQuery)
        );
        DerivedState derived = deriveState(command.modules());
        return new ValidatedCommand(
                command.userId(),
                command.sessionId(),
                command.subject(),
                command.plan(),
                canonicalQuery,
                queryHash,
                derived.state(),
                derived.requiredComplete(),
                derived.allModulesComplete(),
                command.issues(),
                command.modules()
        );
    }

    private DerivedState deriveState(List<ProjectPanoramaResult.ModuleResult> modules) {
        int completeCount = 0;
        boolean requiredComplete = true;
        for (ProjectPanoramaResult.ModuleResult module : modules) {
            boolean complete = StringUtils.hasText(module.snapshotId())
                    && (module.status() == DatasetExecutionStatus.EMPTY
                    || (module.status() == DatasetExecutionStatus.SUCCESS
                    && module.dataComplete()));
            if (complete) {
                completeCount++;
            } else if (module.required()) {
                requiredComplete = false;
            }
        }
        PanoramaExecutionState state = completeCount == modules.size()
                ? PanoramaExecutionState.COMPLETE
                : completeCount == 0
                ? PanoramaExecutionState.FAILED
                : PanoramaExecutionState.PARTIAL_SUCCESS;
        return new DerivedState(state, requiredComplete, completeCount == modules.size());
    }

    private Map<String, BusinessSnapshot> loadReferencedSnapshots(
            ValidatedCommand command,
            LocalDateTime now) {
        List<String> ids = command.modules().stream()
                .map(ProjectPanoramaResult.ModuleResult::snapshotId)
                .filter(StringUtils::hasText)
                .toList();
        if (ids.size() != new HashSet<>(ids).size()) {
            throw new IllegalArgumentException("项目全景模块快照引用重复");
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        /* 与清理任务使用同一行锁，防止校验通过后模块快照在聚合引用写入前被删除。 */
        List<BusinessSnapshot> rows = businessSnapshotMapper.selectByIdsForUpdate(ids);
        Map<String, BusinessSnapshot> snapshots = new HashMap<>();
        if (rows != null) {
            rows.forEach(row -> snapshots.put(row.getSnapshotId(), row));
        }
        if (snapshots.size() != ids.size()) {
            throw new IllegalStateException("项目全景引用的业务快照不存在");
        }
        for (ProjectPanoramaResult.ModuleResult module : command.modules()) {
            if (StringUtils.hasText(module.snapshotId())) {
                validateSnapshot(command, module, snapshots.get(module.snapshotId()), now);
            }
        }
        return Map.copyOf(snapshots);
    }

    private void validateSnapshot(
            ValidatedCommand command,
            ProjectPanoramaResult.ModuleResult module,
            BusinessSnapshot snapshot,
            LocalDateTime now) {
        boolean validStatus = snapshot != null
                && Set.of("COMPLETE", "PARTIAL_SUCCESS").contains(snapshot.getStatus());
        boolean validOwner = snapshot != null
                && Objects.equals(command.userId(), snapshot.getUserId())
                && Objects.equals(command.sessionId(), snapshot.getSessionId());
        boolean validSubject = snapshot != null
                && Objects.equals("PROJECT", snapshot.getSubjectType())
                && Objects.equals(command.subject().projectId(), snapshot.getSubjectId());
        boolean validDataset = snapshot != null
                && Objects.equals(module.datasetCode(), snapshot.getDatasetCode());
        boolean validQuery = snapshot != null
                && Objects.equals(command.queryHash(), snapshot.getQueryHash());
        boolean validExecution = snapshot != null && module.executionResult() != null
                && Objects.equals(
                        snapshot.getConfigChecksum(),
                        module.executionResult().source().datasetConfigChecksum()
                )
                && Objects.equals(
                        snapshot.getFieldPolicyChecksum(),
                        module.executionResult().source().fieldPolicyChecksum()
                )
                && Objects.equals(snapshot.getDataComplete(), module.dataComplete());
        boolean validExpiry = snapshot != null && snapshot.getExpiresAt() != null
                && snapshot.getExpiresAt().isAfter(now);
        if (!validStatus || !validOwner || !validSubject || !validDataset
                || !validQuery || !validExecution || !validExpiry) {
            throw new IllegalStateException("项目全景模块快照已失效或不属于当前项目会话");
        }
    }

    private LocalDateTime calculateExpiry(
            java.util.Collection<BusinessSnapshot> snapshots,
            LocalDateTime now) {
        /* 全部模块失败时仍保留短期执行锚点，便于回答明确表达失败状态。 */
        return snapshots.stream()
                .map(BusinessSnapshot::getExpiresAt)
                .min(LocalDateTime::compareTo)
                .orElseGet(() -> now.plusMinutes(5));
    }

    private ProjectPanoramaSnapshot aggregate(
            ValidatedCommand command,
            LocalDateTime expiresAt,
            LocalDateTime now) {
        String queryJson = writeJson(command.canonicalQuery());
        ProjectPanoramaSnapshot aggregate = new ProjectPanoramaSnapshot();
        aggregate.setPanoramaSnapshotId(UUID.randomUUID().toString().replace("-", ""));
        aggregate.setSessionId(command.sessionId());
        aggregate.setUserId(command.userId());
        aggregate.setProjectId(command.subject().projectId());
        aggregate.setProjectCode(command.subject().projectCode());
        aggregate.setProjectType(command.subject().projectType());
        aggregate.setProfileId(command.plan().profileId());
        aggregate.setProfileChecksum(command.plan().configChecksum());
        aggregate.setQueryJson(queryJson);
        aggregate.setQueryHash(command.queryHash());
        aggregate.setStatus(command.state().name());
        aggregate.setRequiredComplete(command.requiredComplete());
        aggregate.setAllModulesComplete(command.allModulesComplete());
        aggregate.setIssuesJson(writeJson(command.issues()));
        aggregate.setExpiresAt(expiresAt);
        aggregate.setCreatedAt(now);
        aggregate.setCompletedAt(now);
        return aggregate;
    }

    private ProjectPanoramaSnapshotModule reference(
            String aggregateId,
            ProjectPanoramaResult.ModuleResult module,
            int index,
            LocalDateTime now) {
        ProjectPanoramaSnapshotModule reference = new ProjectPanoramaSnapshotModule();
        reference.setPanoramaSnapshotId(aggregateId);
        reference.setDatasetCode(module.datasetCode());
        reference.setSnapshotId(module.snapshotId());
        reference.setRequiredFlag(module.required());
        reference.setDisplayOrder(index + 1);
        reference.setStatus(module.status().name());
        reference.setCreatedAt(now);
        return reference;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("项目全景安全数据无法序列化", exception);
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }

    private void requireChecksum(String value, String field) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(field + "必须是64位SHA-256十六进制");
        }
    }

    public record CreateCommand(
            String userId,
            String sessionId,
            AuthorizedProjectSubject subject,
            ProjectPanoramaPlan plan,
            Map<String, Object> canonicalQuery,
            List<ProjectIssueResult> issues,
            List<ProjectPanoramaResult.ModuleResult> modules) {

        public CreateCommand {
            canonicalQuery = canonicalQuery == null ? Map.of() : Map.copyOf(canonicalQuery);
            issues = issues == null ? List.of() : List.copyOf(issues);
            modules = modules == null ? List.of() : List.copyOf(modules);
        }
    }

    private record ValidatedCommand(
            String userId,
            String sessionId,
            AuthorizedProjectSubject subject,
            ProjectPanoramaPlan plan,
            Map<String, Object> canonicalQuery,
            String queryHash,
            PanoramaExecutionState state,
            boolean requiredComplete,
            boolean allModulesComplete,
            List<ProjectIssueResult> issues,
            List<ProjectPanoramaResult.ModuleResult> modules) {
    }

    private record DerivedState(
            PanoramaExecutionState state,
            boolean requiredComplete,
            boolean allModulesComplete) {
    }

    /**
     * 对外只返回后续追问需要的安全引用，不暴露持久化实体中的身份和查询字段。
     */
    public record SnapshotReference(
            String panoramaSnapshotId,
            String status,
            LocalDateTime expiresAt) {
    }
}
