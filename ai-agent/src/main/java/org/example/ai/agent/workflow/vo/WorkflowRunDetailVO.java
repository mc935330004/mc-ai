package org.example.ai.agent.workflow.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.trace.entity.RunStep;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.entity.WorkflowRunItem;

import java.util.List;

/**
 * RunOps工作流运行详情。
 */
@Data
@Builder
public class WorkflowRunDetailVO {

    private WorkflowRun run;

    private List<WorkflowRunItem> items;

    private List<RunStep> steps;
}