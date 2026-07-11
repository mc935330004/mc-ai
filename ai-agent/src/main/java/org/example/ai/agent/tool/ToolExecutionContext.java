package org.example.ai.agent.tool;

import lombok.Builder;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行上下文。
 *
 * 一次 Agent 运行过程中，所有步骤共享同一个上下文。
 * variables 用来保存每一步的输出结果，后续步骤可以通过 inputRef 引用。
 */
@Data
@Builder
public class ToolExecutionContext {

    /**
     * 本次 Agent 运行 ID，用于串联 Trace、Step、ToolCallLog。
     */
    private String runId;

    /**
     * 当前用户 ID，后续做权限控制时会用到。
     */
    private String userId;

    /**
     * 变量池。
     *
     * 示例：
     * {
     *   "project": {"id": 1001, "projectName": "A项目"},
     *   "contracts": [...]
     * }
     */
    private Map<String, Object> variables;

    /**
     * 用户上下文。
     *
     * 可以放页面上下文、当前项目 ID、当前组织等信息。
     */
    private Map<String, Object> userContext;

    /**
     * 调用业务系统使用的认证信息，不允许写入日志和数据库
     */
    private String authorization;
}