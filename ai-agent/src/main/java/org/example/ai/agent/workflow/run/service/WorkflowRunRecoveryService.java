package org.example.ai.agent.workflow.run.service;

import java.time.LocalDateTime;

/**
 * 工作流异常中断恢复服务。
 */
public interface WorkflowRunRecoveryService {

    /**
     * 将超时未结束的 RUNNING 记录标记为 FAILED。
     *
     * @param cutoff     超时截止时间
     * @param recoveredAt 本次恢复时间
     * @return 更新记录数量
     */
    int recoverInterruptedRuns(
            LocalDateTime cutoff,
            LocalDateTime recoveredAt
    );
}