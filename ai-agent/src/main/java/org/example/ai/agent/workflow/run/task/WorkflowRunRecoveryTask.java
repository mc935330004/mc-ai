package org.example.ai.agent.workflow.run.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.workflow.run.service.WorkflowRunRecoveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 工作流卡死运行恢复任务。
 *
 * 处理以下场景：
 * 1. 服务执行期间被强制停止；
 * 2. 容器或服务器重启；
 * 3. 运行线程非正常中断；
 * 4. 工作流执行时间超过允许上限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty( prefix = "ai.workflow.run-recovery", name = "enabled", havingValue = "true",matchIfMissing = true)
public class WorkflowRunRecoveryTask {

    private final WorkflowRunRecoveryService recoveryService;

    /**
     * RUNNING 状态最大存活时间。
     *
     * 多项目最多查询 5 个，
     * 第一版设置为 30 分钟已经足够宽松。
     */
    @Value("${ai.workflow.run-recovery.timeout-minutes:30}")
    private long timeoutMinutes;

    /**
     * 服务启动后以及固定周期执行恢复。
     *
     * 多实例部署也是安全的：
     * SQL 只更新 status=RUNNING 的记录，
     * 第一个实例更新后，其他实例不会重复更新。
     */
    @Scheduled(
            initialDelayString =
                    "${ai.workflow.run-recovery.initial-delay-ms:30000}",
            fixedDelayString =
                    "${ai.workflow.run-recovery.fixed-delay-ms:60000}"
    )
    public void recoverInterruptedRuns() {

        LocalDateTime recoveredAt =
                LocalDateTime.now();

        long safeTimeoutMinutes =
                Math.max(
                        1L,
                        timeoutMinutes
                );

        LocalDateTime cutoff =
                recoveredAt.minusMinutes(
                        safeTimeoutMinutes
                );

        try {
            int affected =
                    recoveryService
                            .recoverInterruptedRuns(
                                    cutoff,
                                    recoveredAt
                            );

            if (affected > 0) {
                log.warn(
                        "已恢复卡死工作流运行记录，数量={}，截止时间={}",
                        affected,
                        cutoff
                );
            }

        } catch (RuntimeException exception) {

            /*
             * 定时恢复失败不能导致调度线程停止。
             * 下一轮调度仍然需要继续尝试。
             */
            log.error(
                    "卡死工作流运行记录恢复失败，截止时间={}",
                    cutoff,
                    exception
            );
        }
    }
}