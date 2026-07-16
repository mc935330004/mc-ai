package org.example.ai.agent.workflow.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.entity.WorkflowVersion;

/**
 * 工作流管理详情。
 */
@Data
@Builder
public class WorkflowDetailVO {

    private WorkflowDefinition definition;

    /**
     * 从未发布时为空。
     */
    private WorkflowVersion activeVersion;
}