package org.example.ai.agent.workflow.vo;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.graph.compiler.GraphValidationError;

import java.util.List;

/**
 * 工作流发布结果。
 */
@Data
@Builder
public class WorkflowPublishResultVO {

    private Boolean published;

    /**
     * true表示草稿与当前版本相同，没有创建重复版本。
     */
    private Boolean reused;

    private Long workflowId;

    private String workflowCode;

    private Long versionId;

    private Integer versionNo;

    private Integer configRevision;

    private String configChecksum;

    private List<GraphValidationError> errors;
}