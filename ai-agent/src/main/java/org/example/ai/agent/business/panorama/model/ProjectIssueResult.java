package org.example.ai.agent.business.panorama.model;

import java.util.List;

/**
 * 后端确定性计算产生的正式项目问题结论。
 */
public record ProjectIssueResult(
        String ruleCode,
        IssueMatchStatus status,
        String severity,
        String message,
        List<String> evidenceFactCodes) {

    public ProjectIssueResult {
        evidenceFactCodes = evidenceFactCodes == null
                ? List.of()
                : List.copyOf(evidenceFactCodes);
    }
}
