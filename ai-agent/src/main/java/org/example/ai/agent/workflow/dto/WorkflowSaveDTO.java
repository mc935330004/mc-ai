package org.example.ai.agent.workflow.dto;

import lombok.Data;

/**
 * 新增或修改工作流草稿。
 */
@Data
public class WorkflowSaveDTO {

    /**
     * 为空表示新增，不为空表示修改。
     */
    private Long id;

    private String workflowCode;

    private String workflowName;

    private String description;

    /**
     * 前端工作流编辑器生成的GraphSpec。
     */
    private String graphSpecJson;
}