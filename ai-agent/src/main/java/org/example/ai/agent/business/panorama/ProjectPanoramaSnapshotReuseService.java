package org.example.ai.agent.business.panorama;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshot;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotModuleMapper;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.enums.SnapshotReadMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 使用当前身份、当前全景方案和当前字段策略安全恢复项目全景快照。
 */
@Service
public class ProjectPanoramaSnapshotReuseService {

    private static final int MAX_JSON_BYTES = 256 * 1024;
    private static final int MAX_MODULES = 32;
    private static final int MAX_ISSUES = 256;
    private static final Set<String> BUSINESS_SNAPSHOT_STATUSES = Set.of(
            "COMPLETE", "PARTIAL_SUCCESS"
    );
    private static final Set<DatasetExecutionStatus> TERMINAL_STATUSES = Set.of(
            DatasetExecutionStatus.SUCCESS,
            DatasetExecutionStatus.EMPTY,
            DatasetExecutionStatus.DENIED,
            DatasetExecutionStatus.FAILED,
            DatasetExecutionStatus.TIMEOUT
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ProjectIssueResult>> ISSUE_LIST_TYPE =
            new TypeReference<>() {
            };

    private final ProjectSubjectAuthorizationService authorizationService;
    private final ProjectPanoramaProfileService profileService;
    private final BusinessSnapshotAccessService accessService;
    private final ProjectPanoramaSnapshotMapper snapshotMapper;
    private final ProjectPanoramaSnapshotModuleMapper moduleMapper;
    private final BusinessSnapshotMapper businessSnapshotMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ProjectPanoramaSnapshotReuseService(
            ProjectSubjectAuthorizationService authorizationService,
            ProjectPanoramaProfileService profileService,
            BusinessSnapshotAccessService accessService,
            ProjectPanoramaSnapshotMapper snapshotMapper,
            ProjectPanoramaSnapshotModuleMapper moduleMapper,
            BusinessSnapshotMapper businessSnapshotMapper,
            ObjectMapper objectMapper) {
        this(
                authorizationService,
                profileService,
                accessService,
                snapshotMapper,
                moduleMapper,
                businessSnapshotMapper,
                objectMapper,
                Clock.systemDefaultZone()
        );
    }

    ProjectPanoramaSnapshotReuseService(
            ProjectSubjectAuthorizationService authorizationService,
            ProjectPanoramaProfileService profileService,
            BusinessSnapshotAccessService accessService,
            ProjectPanoramaSnapshotMapper snapshotMapper,
            ProjectPanoramaSnapshotModuleMapper moduleMapper,
            BusinessSnapshotMapper businessSnapshotMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.authorizationService = Objects.requireNonNull(
                authorizationService, "authorizationService不能为空"
        );
        this.profileService = Objects.requireNonNull(profileService, "profileService不能为空");
        this.accessService = Objects.requireNonNull(accessService, "accessService不能为空");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.moduleMapper = Objects.requireNonNull(moduleMapper, "moduleMapper不能为空");
        this.businessSnapshotMapper = Objects.requireNonNull(
                businessSnapshotMapper, "businessSnapshotMapper不能为空"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /**
     * 任一身份、配置、引用或安全事实不一致都统一返回空，避免泄露失败原因。
     */
    public Optional<ProjectPanoramaResult> reuse(ReuseCommand command) {
        if (!valid(command)
                || command.readMode() == SnapshotReadMode.FORCE_LIVE) {
            return Optional.empty();
        }
        try {
            ProjectPanoramaCommand authorizationCommand = new ProjectPanoramaCommand(
                    command.agentRunId(),
                    command.userId(),
                    command.sessionId(),
                    command.authorization(),
                    command.secureContext(),
                    command.selectionToken(),
                    command.canonicalQuery()
            );
            AuthorizedProjectSubject subject = authorizationService.authorize(
                    authorizationCommand
            );
            ProjectPanoramaPlan plan = profileService.resolve(subject.projectType()).select(command.scope());
            if (plan.modules().isEmpty() || plan.modules().size() > MAX_MODULES) {
                return Optional.empty();
            }
            Map<String, Object> canonicalQuery = moduleQuery(command.canonicalQuery(), subject);
            String queryHash = ContentHashUtils.sha256(
                    ReportDatasetValidator.canonicalSafeValue(canonicalQuery)
            );
            ProjectPanoramaSnapshot aggregate = snapshotMapper.selectById(
                    command.panoramaSnapshotId()
            );
            LocalDateTime now = LocalDateTime.now(clock);
            if (!aggregateUsable(aggregate, command, subject, plan, queryHash, now)) {
                return Optional.empty();
            }
            List<ProjectPanoramaSnapshotModule> references = moduleMapper.selectList(
                    Wrappers.<ProjectPanoramaSnapshotModule>lambdaQuery()
                            .eq(
                                    ProjectPanoramaSnapshotModule::getPanoramaSnapshotId,
                                    command.panoramaSnapshotId()
                            )
                            .orderByAsc(
                                    ProjectPanoramaSnapshotModule::getDisplayOrder,
                                    ProjectPanoramaSnapshotModule::getId
                            )
            );
            if (!referencesUsable(references, plan, command.panoramaSnapshotId())) {
                return Optional.empty();
            }
            List<ProjectPanoramaResult.ModuleResult> modules = restoreModules(
                    command, subject, plan, references, canonicalQuery, queryHash, now
            );
            if (modules == null || !integrityMatches(aggregate, modules)) {
                return Optional.empty();
            }
            List<ProjectIssueResult> issues = readIssues(aggregate.getIssuesJson());
            if (issues == null) {
                return Optional.empty();
            }
            return Optional.of(new ProjectPanoramaResult(
                    plan.profileId(),
                    plan.configChecksum(),
                    aggregate.getPanoramaSnapshotId(),
                    PanoramaExecutionState.valueOf(aggregate.getStatus()),
                    Boolean.TRUE.equals(aggregate.getRequiredComplete()),
                    Boolean.TRUE.equals(aggregate.getAllModulesComplete()),
                    missingRequired(modules),
                    issues,
                    modules
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private boolean valid(ReuseCommand command) {
        return command != null
                && validText(command.agentRunId(), 128)
                && validText(command.userId(), 128)
                && validText(command.sessionId(), 64)
                && validText(command.authorization(), 8192)
                && validText(command.selectionToken(), 4096)
                && validText(command.panoramaSnapshotId(), 64)
                && command.scope() != null
                && command.readMode() != null;
    }

    private boolean validText(String value, int maxLength) {
        return StringUtils.hasText(value) && value.length() <= maxLength;
    }

    private Map<String, Object> moduleQuery(
            Map<String, Object> query,
            AuthorizedProjectSubject subject) {
        Map<String, Object> result = new LinkedHashMap<>(query);
        result.remove("projectYear");
        result.put("projectCode", subject.projectCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>)
                ReportDatasetValidator.freezeSafeValue(result);
        return frozen;
    }

    private boolean aggregateUsable(
            ProjectPanoramaSnapshot snapshot,
            ReuseCommand command,
            AuthorizedProjectSubject subject,
            ProjectPanoramaPlan plan,
            String queryHash,
            LocalDateTime now) {
        if (snapshot == null
                || !Objects.equals(snapshot.getPanoramaSnapshotId(), command.panoramaSnapshotId())
                || !Objects.equals(snapshot.getUserId(), command.userId())
                || !Objects.equals(snapshot.getSessionId(), command.sessionId())
                || !Objects.equals(snapshot.getProjectId(), subject.projectId())
                || !Objects.equals(snapshot.getProjectCode(), subject.projectCode())
                || !Objects.equals(snapshot.getProjectType(), subject.projectType())
                || !Objects.equals(snapshot.getProfileId(), plan.profileId())
                || !Objects.equals(snapshot.getProfileChecksum(), plan.configChecksum())
                || !Objects.equals(snapshot.getQueryHash(), queryHash)
                || snapshot.getRequiredComplete() == null
                || snapshot.getAllModulesComplete() == null
                || snapshot.getExpiresAt() == null
                || !snapshot.getExpiresAt().isAfter(now)) {
            return false;
        }
        try {
            PanoramaExecutionState.valueOf(snapshot.getStatus());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean referencesUsable(
            List<ProjectPanoramaSnapshotModule> references,
            ProjectPanoramaPlan plan,
            String panoramaSnapshotId) {
        if (references == null || references.size() != plan.modules().size()) {
            return false;
        }
        for (int index = 0; index < references.size(); index++) {
            ProjectPanoramaSnapshotModule reference = references.get(index);
            ProjectPanoramaPlan.Module configured = plan.modules().get(index);
            DatasetExecutionStatus status = status(reference);
            if (reference == null
                    || !Objects.equals(reference.getPanoramaSnapshotId(), panoramaSnapshotId)
                    || !Objects.equals(reference.getDatasetCode(), configured.datasetCode())
                    || !Objects.equals(reference.getRequiredFlag(), configured.required())
                    || !Objects.equals(reference.getDisplayOrder(), index + 1)
                    || status == null
                    || !StringUtils.hasText(reference.getSnapshotId())
                    || (status != DatasetExecutionStatus.SUCCESS
                    && status != DatasetExecutionStatus.EMPTY)) {
                return false;
            }
        }
        return true;
    }

    private DatasetExecutionStatus status(ProjectPanoramaSnapshotModule reference) {
        if (reference == null) {
            return null;
        }
        try {
            DatasetExecutionStatus status = DatasetExecutionStatus.valueOf(reference.getStatus());
            return TERMINAL_STATUSES.contains(status) ? status : null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<ProjectPanoramaResult.ModuleResult> restoreModules(
            ReuseCommand command,
            AuthorizedProjectSubject subject,
            ProjectPanoramaPlan plan,
            List<ProjectPanoramaSnapshotModule> references,
            Map<String, Object> canonicalQuery,
            String queryHash,
            LocalDateTime now) {
        List<ProjectPanoramaResult.ModuleResult> results = new ArrayList<>();
        for (int index = 0; index < references.size(); index++) {
            ProjectPanoramaSnapshotModule reference = references.get(index);
            ProjectPanoramaPlan.Module configured = plan.modules().get(index);
            Optional<BusinessSnapshotAccessService.AccessGrant> grant = accessService.reauthorize(
                    new BusinessSnapshotAccessService.AccessCommand(
                            command.agentRunId(),
                            command.userId(),
                            command.sessionId(),
                            command.authorization(),
                            command.secureContext(),
                            configured.datasetCode(),
                            BusinessSubjectType.PROJECT,
                            subject.projectId(),
                            canonicalQuery
                    )
            );
            if (grant.isEmpty()) {
                return null;
            }
            DatasetExecutionStatus status = status(reference);
            BusinessSnapshot snapshot = businessSnapshotMapper.selectById(
                    reference.getSnapshotId()
            );
            if (!businessSnapshotUsable(
                    snapshot, reference, command, subject, configured,
                    grant.orElseThrow(), queryHash, now
            )) {
                return null;
            }
            SafeChannels channels = readChannels(
                    snapshot.getFactsJson(), configured.datasetCode()
            );
            if (channels == null) {
                return null;
            }
            results.add(new ProjectPanoramaResult.ModuleResult(
                    configured.datasetCode(),
                    configured.required(),
                    status,
                    Boolean.TRUE.equals(snapshot.getDataComplete()),
                    snapshot.getSnapshotId(),
                    null,
                    channels.display(),
                    channels.model(),
                    snapshot.getFieldPolicyChecksum()
            ));
        }
        return List.copyOf(results);
    }

    private boolean businessSnapshotUsable(
            BusinessSnapshot snapshot,
            ProjectPanoramaSnapshotModule reference,
            ReuseCommand command,
            AuthorizedProjectSubject subject,
            ProjectPanoramaPlan.Module configured,
            BusinessSnapshotAccessService.AccessGrant grant,
            String queryHash,
            LocalDateTime now) {
        return snapshot != null
                && Objects.equals(snapshot.getSnapshotId(), reference.getSnapshotId())
                && Objects.equals(snapshot.getUserId(), command.userId())
                && Objects.equals(snapshot.getSessionId(), command.sessionId())
                && Objects.equals(snapshot.getSubjectType(), BusinessSubjectType.PROJECT.name())
                && Objects.equals(snapshot.getSubjectId(), subject.projectId())
                && Objects.equals(snapshot.getDatasetCode(), configured.datasetCode())
                && Objects.equals(snapshot.getQueryHash(), queryHash)
                && Objects.equals(snapshot.getConfigChecksum(), grant.configChecksum())
                && Objects.equals(
                        snapshot.getFieldPolicyChecksum(), grant.fieldPolicyChecksum()
                )
                && snapshot.getDataComplete() != null
                && BUSINESS_SNAPSHOT_STATUSES.contains(snapshot.getStatus())
                && snapshot.getExpiresAt() != null
                && snapshot.getExpiresAt().isAfter(now)
                && withinReadWindow(
                snapshot,
                command.readMode(),
                grant.ttlMinutes(),
                now
        );
    }

    /**
     * 明确历史引用只要求仍在保留期内，普通追问还要求业务事实仍然新鲜。
     */
    private boolean withinReadWindow(
            BusinessSnapshot snapshot,
            SnapshotReadMode readMode,
            int ttlMinutes,
            LocalDateTime now) {
        if (readMode == SnapshotReadMode.REUSE_SNAPSHOT) {
            return true;
        }
        return snapshot.getCompletedAt() != null
                && snapshot.getCompletedAt().plusMinutes(ttlMinutes).isAfter(now);
    }

    @SuppressWarnings("unchecked")
    private SafeChannels readChannels(String factsJson, String datasetCode) {
        if (!validJson(factsJson)) {
            return null;
        }
        try {
            Map<String, Object> root = objectMapper.readerFor(MAP_TYPE)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(factsJson);
            if (root.size() != 1 || !(root.get(datasetCode) instanceof Map<?, ?> item)
                    || !(item.get("display") instanceof Map<?, ?> display)
                    || !(item.get("model") instanceof Map<?, ?> model)) {
                return null;
            }
            return new SafeChannels(
                    stringMap((Map<?, ?>) display),
                    stringMap((Map<?, ?>) model)
            );
        } catch (Exception exception) {
            return null;
        }
    }

    private Map<String, Object> stringMap(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || result.putIfAbsent(key, entry.getValue()) != null) {
                throw new IllegalArgumentException("安全事实通道不合法");
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = (Map<String, Object>)
                ReportDatasetValidator.freezeSafeValue(result);
        return frozen;
    }

    private List<ProjectIssueResult> readIssues(String issuesJson) {
        if (!validJson(issuesJson)) {
            return null;
        }
        try {
            List<ProjectIssueResult> issues = objectMapper.readValue(
                    issuesJson, ISSUE_LIST_TYPE
            );
            if (issues == null || issues.size() > MAX_ISSUES || issues.stream().anyMatch(
                    Objects::isNull
            )) {
                return null;
            }
            return List.copyOf(issues);
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean validJson(String value) {
        return StringUtils.hasText(value)
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES;
    }

    private boolean integrityMatches(
            ProjectPanoramaSnapshot aggregate,
            List<ProjectPanoramaResult.ModuleResult> modules) {
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
        return Objects.equals(aggregate.getStatus(), state.name())
                && Objects.equals(aggregate.getRequiredComplete(), requiredComplete)
                && Objects.equals(
                        aggregate.getAllModulesComplete(), completeCount == modules.size()
                );
    }

    private List<String> missingRequired(List<ProjectPanoramaResult.ModuleResult> modules) {
        return modules.stream()
                .filter(ProjectPanoramaResult.ModuleResult::required)
                .filter(module -> !StringUtils.hasText(module.snapshotId())
                        || (module.status() != DatasetExecutionStatus.EMPTY
                        && (module.status() != DatasetExecutionStatus.SUCCESS
                        || !module.dataComplete())))
                .map(ProjectPanoramaResult.ModuleResult::datasetCode)
                .toList();
    }

    public record ReuseCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String selectionToken,
            String panoramaSnapshotId,
            ProjectPanoramaPlan.Scope scope,
            SnapshotReadMode readMode,
            Map<String, Object> canonicalQuery) {

        @SuppressWarnings("unchecked")
        public ReuseCommand {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            canonicalQuery = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    canonicalQuery == null ? Map.of() : canonicalQuery
            );
        }

        /** 认证、身份、选择令牌、快照ID和查询值均不进入日志。 */
        @Override
        public String toString() {
            return "ReuseCommand[authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", selectionTokenPresent=" + StringUtils.hasText(selectionToken)
                    + ", panoramaSnapshotPresent=" + StringUtils.hasText(panoramaSnapshotId)
                    + ", analysisMode=" + (scope == null ? null : scope.mode())
                    + ", readMode=" + readMode
                    + ", canonicalQuerySize=" + canonicalQuery.size() + ']';
        }
    }

    private record SafeChannels(
            Map<String, Object> display,
            Map<String, Object> model) {
    }
}
