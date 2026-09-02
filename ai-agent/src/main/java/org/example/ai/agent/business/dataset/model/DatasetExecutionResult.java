package org.example.ai.agent.business.dataset.model;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.DatasetExecutionStatus;

import java.util.Map;

/**
 * 报告数据集安全执行结果。
 *
 * safeFacts 只能包含经过字段策略处理后的安全事实，字符串表示只展示通道名称。
 */
public record DatasetExecutionResult(
        String datasetCode,
        DatasetExecutionStatus status,
        boolean dataComplete,
        Map<String, Object> safeFacts,
        String workflowRunId,
        String resultArtifactId,
        String safeErrorCode,
        String safeMessage) {

    @SuppressWarnings("unchecked")
    public DatasetExecutionResult {
        Object frozen = ReportDatasetValidator.freezeSafeValue(
                safeFacts == null ? Map.of() : safeFacts
        );
        if (!(frozen instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("safeFacts必须是Map");
        }
        safeFacts = (Map<String, Object>) frozen;
    }

    /**
     * 不输出任何事实值，避免日志或异常消息意外携带业务数据。
     */
    @Override
    public String toString() {
        return "DatasetExecutionResult["
                + "datasetCode=" + datasetCode
                + ", status=" + status
                + ", dataComplete=" + dataComplete
                + ", safeFactChannels=" + safeFacts.keySet()
                + ", workflowRunId=" + workflowRunId
                + ", resultArtifactId=" + resultArtifactId
                + ", safeErrorCode=" + safeErrorCode
                + ", safeMessage=" + safeMessage
                + ']';
    }
}
