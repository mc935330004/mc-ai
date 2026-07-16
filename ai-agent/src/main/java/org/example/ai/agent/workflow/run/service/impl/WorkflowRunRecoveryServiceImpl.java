package org.example.ai.agent.workflow.run.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunMapper;
import org.example.ai.agent.workflow.run.service.WorkflowRunRecoveryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 工作流异常中断恢复服务实现。
 */
@Service
@RequiredArgsConstructor
public class WorkflowRunRecoveryServiceImpl implements WorkflowRunRecoveryService {

    private static final String RECOVERY_ERROR_CODE ="WORKFLOW_RUN_INTERRUPTED";

    private static final String RECOVERY_ERROR_MESSAGE =
            "服务重启或执行超时，工作流运行未正常结束";

    private final WorkflowRunMapper workflowRunMapper;

    @Override
    public int recoverInterruptedRuns(
            LocalDateTime cutoff,
            LocalDateTime recoveredAt) {

        if (cutoff == null) {
            throw new IllegalArgumentException(
                    "工作流恢复截止时间不能为空"
            );
        }

        if (recoveredAt == null) {
            throw new IllegalArgumentException(
                    "工作流恢复时间不能为空"
            );
        }

        if (cutoff.isAfter(recoveredAt)) {
            throw new IllegalArgumentException(
                    "工作流恢复截止时间不能晚于恢复时间"
            );
        }

        return workflowRunMapper.failStaleRunningRuns(
                cutoff,
                recoveredAt,
                RECOVERY_ERROR_CODE,
                RECOVERY_ERROR_MESSAGE
        );
    }
}