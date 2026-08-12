package org.example.ai.agent.workflow.run.service;

import org.example.ai.agent.workflow.dto.WorkflowDebugRequestDTO;
import org.example.ai.agent.workflow.dto.WorkflowDraftPreviewRequestDTO;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;

/**
 * 工作流草稿调试服务。
 */
public interface WorkflowDebugService {

    /**
     * 快速配置临时运行写入 requestId 的内部标记。
     */
    String DRAFT_PREVIEW_REQUEST_PREFIX =
            "DRAFT_PREVIEW:";

    /**
     * 执行数据库中已经保存的工作流草稿。
     */
    WorkflowExecutionOutcome debug(
            Long workflowId,
            WorkflowDebugRequestDTO request,
            String userId,
            String authorization
    );

    /**
     * 执行前端传入的临时工作流草稿。
     *
     * 不更新数据库中的工作流定义。
     */
    WorkflowExecutionOutcome previewDraft(
            Long workflowId,
            WorkflowDraftPreviewRequestDTO request,
            String userId,
            String authorization
    );
}
