package org.example.ai.agent.alert.event;

/**
 * 工作流运行失败事件。
 *
 * 事件只携带稳定的runId。
 * 监听器在事务提交后重新读取数据库中的最终运行记录。
 *
 * @param runId 工作流运行ID
 */
public record WorkflowRunFailedEvent(String runId) {
}