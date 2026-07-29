package org.example.ai.agent.workflow.run.service.impl;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.alert.event.WorkflowRunFailedEvent;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunMapper;
import org.example.ai.agent.workflow.run.service.WorkflowRunRecoveryService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 恢复异常中断的运行记录。
     *
     * 整个恢复过程处于同一个事务中，
     * 失败事件在事务成功提交后才会被处理。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverInterruptedRuns(
            LocalDateTime cutoff,
            LocalDateTime recoveredAt ) {
        validateTime(
                cutoff,
                recoveredAt
        );

        List<WorkflowRun> candidates =workflowRunMapper.selectStaleRunningRuns(cutoff );

        int affectedCount = 0;

        for (WorkflowRun candidate : candidates) {
            int affected =
                    workflowRunMapper.failRunningRun(
                            candidate.getRunId(),
                            recoveredAt,
                            RECOVERY_ERROR_CODE,
                            RECOVERY_ERROR_MESSAGE
                    );

            /*
             * 多实例同时恢复时，
             * 只有真正更新成功的实例才能发布告警。
             */
            if (affected == 1) {
                affectedCount++;

                eventPublisher.publishEvent(
                        new WorkflowRunFailedEvent(
                                candidate.getRunId()
                        )
                );
            }
        }

        return affectedCount;
    }

    private void validateTime(
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
    }
}