package org.example.ai.agent.alert.event;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.alert.service.AlertService;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

/**
 * 工作流失败事件监听器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowRunFailedEventListener {

    private final WorkflowRunMapper workflowRunMapper;
    private final AlertService alertService;

    /**
     * 只有运行记录事务成功提交后才生成告警。
     *
     * 告警失败只记录日志，不能影响工作流原始结果。
     */
    @TransactionalEventListener( phase = TransactionPhase.AFTER_COMMIT)
    public void handle( WorkflowRunFailedEvent event) {
        if (event == null || !StringUtils.hasText( event.runId())) {
            return;
        }

        try {
            WorkflowRun run = workflowRunMapper.selectOne(
                            Wrappers.<WorkflowRun>lambdaQuery()
                                    .eq(WorkflowRun::getRunId, event.runId()) );

            if (run == null) {
                log.warn(
                        "工作流失败事件对应的运行记录不存在，runId={}",
                        event.runId() );
                return;
            }
            alertService.recordWorkflowFailure(run);

        } catch (RuntimeException exception) {
            /*
             * 告警属于辅助治理能力。
             * 告警异常不能改变已经提交的工作流状态。
             */
            log.error(
                    "工作流失败告警生成失败，runId={}",
                    event.runId(),
                    exception
            );
        }
    }
}