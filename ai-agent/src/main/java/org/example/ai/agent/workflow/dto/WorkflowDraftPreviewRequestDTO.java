package org.example.ai.agent.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 临时工作流草稿预览请求。
 *
 * 本请求只用于执行前端传入的临时 GraphSpec，
 * 不会保存或覆盖数据库中的工作流草稿。
 */
@Data
public class WorkflowDraftPreviewRequestDTO {

    /**
     * 前端当前生成的临时 GraphSpec。
     */
    @NotBlank(message = "临时GraphSpec不能为空")
    @Size(max = 1_048_576,message = "临时GraphSpec不能超过1MB")
    private String graphSpecJson;

    /**
     * 工作流业务输入。
     */
    private Map<String, Object> input = new LinkedHashMap<>();

    /**
     * 当前调试使用的用户上下文。
     *
     * 不允许通过该字段传递 Authorization、
     * Cookie、Token 等安全信息。
     */
    private Map<String, Object> userContext =new LinkedHashMap<>();
}