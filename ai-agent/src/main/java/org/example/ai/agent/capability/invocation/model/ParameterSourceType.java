package org.example.ai.agent.capability.invocation.model;

/**
 * 参数值的来源。
 *
 * 普通参数和安全参数必须明确分离，防止大模型或前端覆盖权限信息。
 */
public enum ParameterSourceType {

    /**
     * 来自工作流的公开输入。
     *
     * 示例：
     * $input.projectName
     */
    INPUT,

    /**
     * 来自上游 GraphSpec 节点产生的变量。
     *
     * 标准路径：
     * $vars.{nodeId}.output
     *
     * 示例：
     * $vars.searchProject.output
     * $vars.projectDetail.output.projectId
     *
     * nodeId 必须与 GraphSpec 中节点的 id 一致。
     */
    VARIABLE,

    /**
     * 来自 ForEach 循环的当前元素。
     *
     * 示例：
     * $item
     * $item.projectName
     */
    ITEM,

    /**
     * 来自安全执行上下文。
     *
     * 示例：
     * $secure.organizationId
     *
     * 该来源不能由前端或大模型赋值。
     */
    SECURE_CONTEXT,

    /**
     * 能力配置中的固定值。
     *
     * 示例：
     * current=1
     * size=20
     *
     * FIXED 不能用于保存 Token、密码等敏感信息。
     */
    FIXED
}