package org.example.ai.agent.tool.projection;

/**
 * 能力输出的两个安全数据视图。
 *
 * @param workflowData 使用稳定机器字段，供工作流下游节点读取
 * @param displayData  使用中文展示字段，供前端和大模型展示
 */
public record CapabilityOutputProjection(
        Object workflowData,
        Object displayData) {
}