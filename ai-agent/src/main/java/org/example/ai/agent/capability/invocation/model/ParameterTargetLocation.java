package org.example.ai.agent.capability.invocation.model;

/**
 * 参数最终写入 HTTP 请求的位置。
 */
public enum ParameterTargetLocation {

    /**
     * URL Path 参数。
     *
     * 示例：
     * /api/project/{projectId}
     */
    PATH,

    /**
     * URL Query 参数。
     *
     * 示例：
     * /api/project/search?keyword=项目A
     */
    QUERY,

    /**
     * JSON RequestBody 参数。
     */
    BODY,

    /**
     * HTTP 请求 Header。
     *
     * 认证、用户、租户等敏感 Header 必须来自 SECURE_CONTEXT，
     * 不能来自大模型输入、普通工作流变量或者固定配置。
     *
     * 示例：
     * Authorization
     * X-Tenant-Id
     * X-Organization-Id
     */
    HEADER
}