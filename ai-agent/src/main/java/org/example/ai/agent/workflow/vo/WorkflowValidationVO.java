package org.example.ai.agent.workflow.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.graph.compiler.GraphValidationError;

import java.util.List;

/**
 * 工作流草稿校验结果。
 *
 * graphPath、nodeId和edgeId用于前端画布定位错误节点。
 */
@Data
@Builder
public class WorkflowValidationVO {

    private Long workflowId;

    private String workflowCode;

    private Integer configRevision;

    private Boolean valid;

    private Integer nodeCount;

    private Integer edgeCount;

    private String draftChecksum;

    private List<GraphValidationError> errors;
}