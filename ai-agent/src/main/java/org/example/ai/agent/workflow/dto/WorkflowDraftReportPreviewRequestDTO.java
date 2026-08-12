package org.example.ai.agent.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 临时工作流报告预览请求。
 *
 * 报告预览复用已有 DEBUG 安全结果，
 * 不会再次调用业务系统。
 */
@Data
public class WorkflowDraftReportPreviewRequestDTO {

    /**
     * 临时工作流执行返回的运行ID。
     */
    @NotBlank(message = "临时运行ID不能为空")
    @Size(max = 64, message = "临时运行ID不能超过64个字符")
    private String runId;

    /**
     * 包含临时报告定义的 GraphSpec。
     */
    @NotBlank(message = "临时GraphSpec不能为空")
    @Size(max = 1_048_576, message = "临时GraphSpec不能超过1MB")
    private String graphSpecJson;
}
