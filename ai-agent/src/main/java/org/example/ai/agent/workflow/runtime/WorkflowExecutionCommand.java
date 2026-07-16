package org.example.ai.agent.workflow.runtime;

import lombok.Builder;
import lombok.Getter;
import org.example.ai.agent.common.enums.WorkflowRunOrigin;
import org.example.ai.agent.common.enums.WorkflowVersionSelection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流执行命令。
 *
 * 不使用@Data，避免authorization进入自动生成的toString。
 */
@Getter
@Builder
public class WorkflowExecutionCommand {

    private String runId;

    private String userId;

    private String workflowCode;

    /**
     * Planner选中工作流时对应的版本ID。
     *
     * 执行时必须再次校验，防止规划和执行之间版本发生切换。
     */
    private Long expectedVersionId;

    @Builder.Default
    private Map<String, Object> input =
            new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> userContext =
            new LinkedHashMap<>();

    private String authorization;

    @Builder.Default
    private Map<String, Object> secureContext =
            new LinkedHashMap<>();
    /**
     * Agent聊天主runId。
     */
    private String agentRunId;

    /**
     * 重试链路根运行ID。
     */
    private String rootRunId;

    /**
     * 失败重试来源运行ID。
     */
    private String sourceRunId;

    /**
     * 幂等请求ID。
     */
    private String requestId;

    @Builder.Default
    private WorkflowRunOrigin origin =
            WorkflowRunOrigin.CHAT;

    @Builder.Default
    private WorkflowVersionSelection versionSelection =
            WorkflowVersionSelection.ACTIVE_PINNED;
}