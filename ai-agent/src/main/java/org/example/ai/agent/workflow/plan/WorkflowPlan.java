package org.example.ai.agent.workflow.plan;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.common.enums.WorkflowPlanStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流规划结果。
 */
@Data
@Builder
public class WorkflowPlan {

    private WorkflowPlanStatus status;

    private String workflowCode;

    private String workflowName;

    /**
     * 规划时选中的发布版本。
     */
    private Long versionId;

    @Builder.Default
    private Map<String, Object> input =
            new LinkedHashMap<>();

    private double confidence;

    private String reason;

    private String clarifyQuestion;

    public boolean isReady() {
        return status == WorkflowPlanStatus.READY;
    }
}