package org.example.ai.agent.business.dataset.model;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;

import java.util.Map;

/**
 * 单次报告数据集执行请求。
 *
 * authorization 与 secureContext 只允许在本次执行内存中传递，不能进入自动生成的字符串表示。
 */
public record DatasetExecutionRequest(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        String datasetCode,
        BusinessSubjectType subjectType,
        String subjectId,
        Map<String, Object> canonicalInput) {

    public DatasetExecutionRequest {
        secureContext = immutableDeepCopy(secureContext);
        canonicalInput = immutableDeepCopy(canonicalInput);
    }

    /**
     * 与规范输入映射共用同一递归安全策略，构造后不再保留调用方的可变嵌套引用。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableDeepCopy(
            Map<String, Object> source) {
        return (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                source == null ? Map.of() : source
        );
    }

    /**
     * 仅展示非敏感摘要，不展示认证、上下文或规范输入的键和值。
     */
    @Override
    public String toString() {
        return "DatasetExecutionRequest["
                + "agentRunId=" + agentRunId
                + ", userId=" + userId
                + ", sessionId=" + sessionId
                + ", authorizationPresent=" + (authorization != null && !authorization.isBlank())
                + ", secureContextPresent=" + !secureContext.isEmpty()
                + ", secureContextSize=" + secureContext.size()
                + ", datasetCode=" + datasetCode
                + ", subjectType=" + subjectType
                + ", subjectId=" + subjectId
                + ", canonicalInputPresent=" + !canonicalInput.isEmpty()
                + ", canonicalInputSize=" + canonicalInput.size()
                + ']';
    }
}
