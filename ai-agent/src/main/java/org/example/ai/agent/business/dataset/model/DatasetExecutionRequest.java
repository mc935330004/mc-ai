package org.example.ai.agent.business.dataset.model;

import org.example.ai.agent.business.model.BusinessSubjectType;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        secureContext = immutableTopLevelCopy(secureContext);
        canonicalInput = immutableTopLevelCopy(canonicalInput);
    }

    private static Map<String, Object> immutableTopLevelCopy(
            Map<String, Object> source) {
        return source == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 仅展示认证是否存在和安全上下文键名，不展示任何认证值。
     */
    @Override
    public String toString() {
        return "DatasetExecutionRequest["
                + "agentRunId=" + agentRunId
                + ", userId=" + userId
                + ", sessionId=" + sessionId
                + ", authorizationPresent=" + (authorization != null && !authorization.isBlank())
                + ", secureContextKeys=" + secureContext.keySet()
                + ", datasetCode=" + datasetCode
                + ", subjectType=" + subjectType
                + ", subjectId=" + subjectId
                + ", canonicalInputKeys=" + canonicalInput.keySet()
                + ']';
    }
}
