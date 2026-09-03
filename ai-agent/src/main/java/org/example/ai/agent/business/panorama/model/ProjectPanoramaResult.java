package org.example.ai.agent.business.panorama.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.DatasetExecutionStatus;

import java.util.List;

/**
 * 一次项目全景的逻辑聚合快照，保留每个模块的安全结果和快照引用。
 */
public record ProjectPanoramaResult(
        Long profileId,
        String profileChecksum,
        String aggregateSnapshotId,
        PanoramaExecutionState state,
        boolean requiredComplete,
        boolean allModulesComplete,
        List<String> missingRequiredDatasetCodes,
        List<ProjectIssueResult> issues,
        List<ModuleResult> modules) {

    public ProjectPanoramaResult {
        missingRequiredDatasetCodes = missingRequiredDatasetCodes == null
                ? List.of()
                : List.copyOf(missingRequiredDatasetCodes);
        issues = issues == null ? List.of() : List.copyOf(issues);
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    /**
     * 模块结果只携带经过字段策略过滤的执行结果，不携带原始响应。
     */
    public record ModuleResult(
            String datasetCode,
            boolean required,
            DatasetExecutionStatus status,
            boolean dataComplete,
            String snapshotId,
            @JsonIgnore DatasetExecutionResult executionResult) {
    }
}
