package org.example.ai.agent.business.panorama.model;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;

import java.util.Map;

/**
 * 已定位到唯一项目后的全景执行命令。
 */
public record ProjectPanoramaCommand(
        String agentRunId,
        String userId,
        String sessionId,
        String authorization,
        Map<String, Object> secureContext,
        String projectSelectionToken,
        Map<String, Object> canonicalQuery) {

    @SuppressWarnings("unchecked")
    public ProjectPanoramaCommand {
        secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                secureContext == null ? Map.of() : secureContext
        );
        canonicalQuery = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                canonicalQuery == null ? Map.of() : canonicalQuery
        );
    }

    /**
     * 认证、项目标识和查询条件都不进入日志字符串。
     */
    @Override
    public String toString() {
        return "ProjectPanoramaCommand["
                + "authorizationPresent=" + (authorization != null && !authorization.isBlank())
                + ", secureContextPresent=" + !secureContext.isEmpty()
                + ", projectSelectionPresent="
                + (projectSelectionToken != null && !projectSelectionToken.isBlank())
                + ", canonicalQuerySize=" + canonicalQuery.size()
                + ']';
    }
}
