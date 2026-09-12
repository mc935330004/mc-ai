package org.example.ai.agent.business.dataset.model;

import org.example.ai.agent.business.model.BusinessSubjectType;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 数据集执行时实际使用的主体、查询和配置来源。
 *
 * 该对象只保存校验信息，不保存认证、角色明细或业务事实。
 */
public record DatasetExecutionSource(
        String userId,
        String sessionId,
        BusinessSubjectType subjectType,
        String subjectId,
        String datasetCode,
        String canonicalInputHash,
        String queryWorkflowCode,
        Long queryWorkflowVersionId,
        String datasetConfigChecksum,
        String fieldPolicyChecksum) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    public DatasetExecutionSource {
        requireText(userId, "userId");
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(subjectType, "subjectType不能为空");
        requireText(subjectId, "subjectId");
        requireText(datasetCode, "datasetCode");
        requireSha256(canonicalInputHash, "canonicalInputHash");
        requireText(queryWorkflowCode, "queryWorkflowCode");
        requireSha256(datasetConfigChecksum, "datasetConfigChecksum");
        requireSha256(fieldPolicyChecksum, "fieldPolicyChecksum");
        if (queryWorkflowVersionId != null && queryWorkflowVersionId <= 0) {
            throw new IllegalArgumentException("queryWorkflowVersionId必须大于0");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不能为空或包含首尾空白");
        }
    }

    private static void requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + "必须是64位SHA-256十六进制");
        }
    }

    /**
     * 日志只显示来源是否具备已解析版本，不输出用户、会话、主体、编码或校验和值。
     */
    @Override
    public String toString() {
        return "DatasetExecutionSource[workflowVersionPresent="
                + (queryWorkflowVersionId != null)
                + ']';
    }
}
