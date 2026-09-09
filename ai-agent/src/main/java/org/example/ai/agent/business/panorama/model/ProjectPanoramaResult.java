package org.example.ai.agent.business.panorama.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.model.DatasetExecutionStatus;

import java.util.List;
import java.util.Map;

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

    /** 不输出聚合快照ID、问题内容、模块引用或任何事实值。 */
    @Override
    public String toString() {
        return "ProjectPanoramaResult[profileId=" + profileId
                + ", profileChecksumPresent="
                + (profileChecksum != null && !profileChecksum.isBlank())
                + ", aggregateSnapshotPresent="
                + (aggregateSnapshotId != null && !aggregateSnapshotId.isBlank())
                + ", state=" + state
                + ", requiredComplete=" + requiredComplete
                + ", allModulesComplete=" + allModulesComplete
                + ", missingRequiredCount=" + missingRequiredDatasetCodes.size()
                + ", issueCount=" + issues.size()
                + ", moduleCount=" + modules.size() + ']';
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
            @JsonIgnore DatasetExecutionResult executionResult,
            Map<String, Object> displayFacts,
            Map<String, Object> modelFacts,
            String fieldPolicyChecksum) {

        @SuppressWarnings("unchecked")
        public ModuleResult {
            displayFacts = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    displayFacts == null ? Map.of() : displayFacts
            );
            modelFacts = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    modelFacts == null ? Map.of() : modelFacts
            );
        }

        /**
         * 保留即时执行路径使用的六参数构造器，公开事实只从已过滤通道派生。
         */
        public ModuleResult(
                String datasetCode,
                boolean required,
                DatasetExecutionStatus status,
                boolean dataComplete,
                String snapshotId,
                DatasetExecutionResult executionResult) {
            this(
                    datasetCode,
                    required,
                    status,
                    dataComplete,
                    snapshotId,
                    executionResult,
                    channel(executionResult, "display"),
                    channel(executionResult, "model"),
                    executionResult == null
                            ? null
                            : executionResult.source().fieldPolicyChecksum()
            );
        }

        private static Map<String, Object> channel(
                DatasetExecutionResult executionResult,
                String channel) {
            if (executionResult == null
                    || !(executionResult.safeFacts().get(channel) instanceof Map<?, ?> values)) {
                return Map.of();
            }
            return values.entrySet().stream()
                    .filter(entry -> entry.getKey() instanceof String)
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            entry -> (String) entry.getKey(),
                            Map.Entry::getValue
                    ));
        }

        /** 不输出事实值、快照引用或策略校验和。 */
        @Override
        public String toString() {
            return "ModuleResult[datasetCode=" + datasetCode
                    + ", required=" + required
                    + ", status=" + status
                    + ", dataComplete=" + dataComplete
                    + ", snapshotPresent=" + (snapshotId != null && !snapshotId.isBlank())
                    + ", displayFactCount=" + displayFacts.size()
                    + ", modelFactCount=" + modelFacts.size()
                    + ", fieldPolicyPresent="
                    + (fieldPolicyChecksum != null && !fieldPolicyChecksum.isBlank()) + ']';
        }
    }
}
