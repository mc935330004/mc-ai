package org.example.ai.agent.business.panorama;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.model.PanoramaExecutionState;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaPlan;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaProgressEvent;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 在唯一项目已定位后，按方案顺序执行项目全景模块。
 */
@Service
public class ProjectPanoramaExecutionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            ProjectPanoramaExecutionService.class
    );
    private static final int MAX_QUERY_BYTES = 64 * 1024;

    private final ProjectSubjectAuthorizationService subjectAuthorizationService;
    private final ProjectPanoramaProfileService profileService;
    private final PanoramaDatasetExecutor datasetExecutor;
    private final BusinessSnapshotService snapshotService;
    private final ProjectIssueRuleService issueRuleService;
    private final ProjectPanoramaSnapshotService panoramaSnapshotService;
    private final int maxModules;
    private final long maxTotalMillis;

    public ProjectPanoramaExecutionService(
            ProjectSubjectAuthorizationService subjectAuthorizationService,
            ProjectPanoramaProfileService profileService,
            PanoramaDatasetExecutor datasetExecutor,
            BusinessSnapshotService snapshotService,
            ProjectIssueRuleService issueRuleService,
            ProjectPanoramaSnapshotService panoramaSnapshotService,
            @Value("${ai.business.panorama.max-modules:32}") int maxModules,
            @Value("${ai.business.panorama.max-total-millis:600000}") long maxTotalMillis) {
        this.subjectAuthorizationService = Objects.requireNonNull(
                subjectAuthorizationService,
                "subjectAuthorizationService不能为空"
        );
        this.profileService = Objects.requireNonNull(profileService, "profileService不能为空");
        this.datasetExecutor = Objects.requireNonNull(
                datasetExecutor,
                "datasetExecutor不能为空"
        );
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService不能为空");
        this.issueRuleService = Objects.requireNonNull(issueRuleService, "issueRuleService不能为空");
        this.panoramaSnapshotService = Objects.requireNonNull(
                panoramaSnapshotService,
                "panoramaSnapshotService不能为空"
        );
        if (maxModules < 1 || maxModules > 32) {
            throw new IllegalArgumentException("项目全景模块上限必须在1到32之间");
        }
        this.maxModules = maxModules;
        if (maxTotalMillis < 100 || maxTotalMillis > 3_600_000) {
            throw new IllegalArgumentException("项目全景总时限必须在100毫秒到1小时之间");
        }
        this.maxTotalMillis = maxTotalMillis;
    }

    /**
     * 只执行用户已经确认且当前项目配置允许的分析模块。
     */
    public ProjectPanoramaResult execute(
            ProjectPanoramaCommand command,
            ProjectPanoramaPlan.Scope scope,
            Consumer<ProjectPanoramaProgressEvent> progressConsumer) {
        validateCommand(command);

        if (scope == null) {
            throw new IllegalArgumentException("项目分析范围不能为空");
        }

        Consumer<ProjectPanoramaProgressEvent> listener = Objects.requireNonNull(
                progressConsumer,
                "progressConsumer不能为空"
        );

        AuthorizedProjectSubject subject = subjectAuthorizationService.authorize(command);

        ProjectPanoramaPlan plan = profileService.resolve(subject.projectType()).select(scope);

        if (plan.modules().isEmpty() || plan.modules().size() > maxModules) {
            throw new IllegalStateException("项目全景模块数量超过执行上限");
        }
        ProjectIssueRuleService.RuleSet ruleSet = issueRuleService.loadRules(plan.profileId());
        Map<String, Object> canonicalInput = moduleInput(command, subject);

        List<ProjectPanoramaResult.ModuleResult> results = new ArrayList<>();

        List<String> missingRequired = new ArrayList<>();

        int completeCount = 0;
        long deadlineNanos =
                System.nanoTime() + maxTotalMillis * 1_000_000L;

        for (int index = 0; index < plan.modules().size(); index++) {
            ProjectPanoramaPlan.Module module = plan.modules().get(index);

            notifyProgress(
                    listener,
                    progress(
                            module,
                            DatasetExecutionStatus.RUNNING,
                            index,
                            plan.modules().size()
                    )
            );
            DatasetExecutionResult executionResult = null;
            DatasetExecutionStatus status = DatasetExecutionStatus.FAILED;
            boolean complete = false;
            String snapshotId = null;
            try {
                int timeoutMs = remainingTimeout(module.timeoutMs(), deadlineNanos);
                PanoramaDatasetExecutor.Result timedResult =
                        datasetExecutor.execute(request(command, subject, module.datasetCode(), canonicalInput), timeoutMs);
                executionResult = timedResult.executionResult();
                status = timedResult.status();

                if (executionResult == null) {
                    throw new ModuleTerminalException(status);
                }
                validateExecutionResult(module, executionResult);
                if (canPersist(executionResult)) {
                    snapshotId = createSnapshot(command, subject, module, canonicalInput, executionResult);
                }

                complete = isComplete(executionResult) && StringUtils.hasText(snapshotId);
            } catch (ModuleTerminalException exception) {
                status = exception.status();
                executionResult = null;
            } catch (RuntimeException ignored) {
                // 单模块系统失败按FAILED收口，后续模块继续执行。
                status = DatasetExecutionStatus.FAILED;
                executionResult = null;
                snapshotId = null;
                complete = false;
                auditModuleFailure(command, module, "EXECUTION_OR_SNAPSHOT");
            }
            notifyProgress(listener, progress(module, status, index, plan.modules().size()));
            if (complete) {
                completeCount++;
            } else if (module.required()) {
                missingRequired.add(module.datasetCode());
            }
            results.add(new ProjectPanoramaResult.ModuleResult(
                            module.datasetCode(),
                            module.required(),
                            status,
                            executionResult != null
                                    && executionResult.dataComplete(),
                            snapshotId,
                            executionResult
                    )
            );
        }
        PanoramaExecutionState state = overallState(completeCount, results.size());

        boolean requiredComplete = missingRequired.isEmpty();

        boolean allModulesComplete = completeCount == results.size();

        List<ProjectIssueResult> issues = issueRuleService.evaluate(ruleSet, results);

        ProjectPanoramaSnapshotService.SnapshotReference aggregate =
                panoramaSnapshotService.create(
                        new ProjectPanoramaSnapshotService.CreateCommand(
                                command.userId(),
                                command.sessionId(),
                                subject,
                                plan,
                                canonicalInput,
                                issues,
                                results
                        )
                );

        return new ProjectPanoramaResult(
                plan.profileId(),
                plan.configChecksum(),
                aggregate.panoramaSnapshotId(),
                state,
                requiredComplete,
                allModulesComplete,
                missingRequired,
                issues,
                results
        );
    }

    /**
     * 查询当前项目类型配置的可用分析模块。
     */
    public ProjectPanoramaPlan configuredPlan(String projectType) {
        return profileService.resolve(projectType);
    }


    private void validateExecutionResult(
            ProjectPanoramaPlan.Module module,
            DatasetExecutionResult result) {
        if (result == null
                || result.status() == null
                || !module.datasetCode().equals(result.datasetCode())) {
            throw new IllegalStateException("项目全景模块返回结果不合法");
        }
    }

    private DatasetExecutionRequest request(
            ProjectPanoramaCommand command,
            AuthorizedProjectSubject subject,
            String datasetCode,
            Map<String, Object> canonicalInput) {
        return new DatasetExecutionRequest(
                command.agentRunId(),
                command.userId(),
                command.sessionId(),
                command.authorization(),
                command.secureContext(),
                datasetCode,
                BusinessSubjectType.PROJECT,
                subject.projectId(),
                canonicalInput
        );
    }

    private String createSnapshot(
            ProjectPanoramaCommand command,
            AuthorizedProjectSubject subject,
            ProjectPanoramaPlan.Module module,
            Map<String, Object> canonicalInput,
            DatasetExecutionResult result) {
        BusinessSnapshot snapshot = snapshotService.create(new BusinessSnapshotService.CreateCommand(
                command.userId(),
                command.sessionId(),
                BusinessSubjectType.PROJECT,
                subject.projectId(),
                module.datasetCode(),
                canonicalInput,
                null,
                List.of(new BusinessSnapshotService.ItemCommand(
                        module.datasetCode(),
                        AssociationType.DIRECT,
                        result,
                        result.status() == DatasetExecutionStatus.EMPTY ? 0 : 1,
                        result.status() == DatasetExecutionStatus.SUCCESS ? 1 : 0,
                        0
                ))
        ));
        return snapshot.getSnapshotId();
    }

    private Map<String, Object> moduleInput(
            ProjectPanoramaCommand command,
            AuthorizedProjectSubject subject) {
        Map<String, Object> input = new LinkedHashMap<>(command.canonicalQuery());
        /* 项目年度仅用于定位项目；模块期间只来自显式 startDate/endDate 等查询条件。 */
        input.remove("projectYear");
        input.put("projectCode", subject.projectCode());
        return Map.copyOf(input);
    }

    private int remainingTimeout(int moduleTimeoutMs, long deadlineNanos) {
        long remainingNanos = deadlineNanos - System.nanoTime();
        long remainingMillis = remainingNanos / 1_000_000L;
        if (remainingMillis < 100) {
            throw new ModuleTerminalException(DatasetExecutionStatus.TIMEOUT);
        }
        return (int) Math.min(moduleTimeoutMs, Math.min(remainingMillis, Integer.MAX_VALUE));
    }

    private void notifyProgress(
            Consumer<ProjectPanoramaProgressEvent> listener,
            ProjectPanoramaProgressEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException ignored) {
            LOGGER.warn("项目全景进度通知失败 datasetCode={}", event.datasetCode());
        }
    }

    private void auditModuleFailure(
            ProjectPanoramaCommand command,
            ProjectPanoramaPlan.Module module,
            String category) {
        LOGGER.warn(
                "项目全景模块失败 category={} datasetCode={} agentRunPresent={}",
                category,
                module.datasetCode(),
                StringUtils.hasText(command.agentRunId())
        );
    }

    private ProjectPanoramaProgressEvent progress(
            ProjectPanoramaPlan.Module module,
            DatasetExecutionStatus status,
            int zeroBasedIndex,
            int total) {
        return new ProjectPanoramaProgressEvent(
                module.datasetCode(),
                status,
                zeroBasedIndex + 1,
                total
        );
    }

    private boolean isComplete(DatasetExecutionResult result) {
        return result != null
                && (result.status() == DatasetExecutionStatus.EMPTY
                || (result.status() == DatasetExecutionStatus.SUCCESS && result.dataComplete()));
    }

    private boolean canPersist(DatasetExecutionResult result) {
        return result != null
                && (result.status() == DatasetExecutionStatus.SUCCESS
                || result.status() == DatasetExecutionStatus.EMPTY);
    }

    private PanoramaExecutionState overallState(int completeCount, int total) {
        if (completeCount == total) {
            return PanoramaExecutionState.COMPLETE;
        }
        return completeCount == 0
                ? PanoramaExecutionState.FAILED
                : PanoramaExecutionState.PARTIAL_SUCCESS;
    }

    private void validateCommand(ProjectPanoramaCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("项目全景命令不能为空");
        }
        requireText(command.agentRunId(), 128, "agentRunId");
        requireText(command.userId(), 128, "userId");
        requireText(command.sessionId(), 64, "sessionId");
        requireText(command.authorization(), 32768, "authorization");
        requireText(command.projectSelectionToken(), 4096, "projectSelectionToken");
        String canonical = ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery());
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw new IllegalArgumentException("项目全景查询条件超过容量上限");
        }
    }

    private void requireText(String value, int maxBytes, String field) {
        if (!StringUtils.hasText(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }

    private static final class ModuleTerminalException extends RuntimeException {
        private final DatasetExecutionStatus status;

        private ModuleTerminalException(DatasetExecutionStatus status) {
            this.status = status == null ? DatasetExecutionStatus.FAILED : status;
        }

        private DatasetExecutionStatus status() {
            return status;
        }
    }
}
