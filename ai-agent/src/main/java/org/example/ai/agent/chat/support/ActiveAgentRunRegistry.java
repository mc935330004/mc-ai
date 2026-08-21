package org.example.ai.agent.chat.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * 保存当前进程内正在执行的Agent聊天任务。
 *
 * 只负责运行中任务协调，
 * 不保存聊天内容和业务数据。
 */
@Component
public class ActiveAgentRunRegistry {

    private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

    /**
     * 注册正在执行的聊天任务。
     */
    public void register(String runId, String userId, String conversationId, Future<?> task) {

        if (!StringUtils.hasText(runId)
                || !StringUtils.hasText(userId)
                || !StringUtils.hasText(conversationId)
                || task == null) {
            throw new IllegalArgumentException(
                    "活动任务注册参数不能为空"
            );
        }

        ActiveRun previous =
                activeRuns.putIfAbsent(
                        runId,
                        new ActiveRun(
                                userId,
                                conversationId,
                                task
                        )
                );

        if (previous != null) {
            throw new IllegalStateException(
                    "Agent运行任务已存在，runId=" + runId
            );
        }
    }

    /**
     * 终止当前用户指定会话中的任务。
     *
     * userId、conversationId、runId必须同时匹配，
     * 禁止只根据runId终止其他用户的任务。
     */
    public boolean cancel(
            String runId,
            String userId,
            String conversationId) {

        ActiveRun activeRun =
                activeRuns.get(runId);

        if (activeRun == null) {
            return false;
        }

        if (!Objects.equals(
                activeRun.userId(),
                userId
        )) {
            return false;
        }

        if (!Objects.equals(
                activeRun.conversationId(),
                conversationId
        )) {
            return false;
        }

        /*
         * 先以CAS方式移除，保证同一个任务只能成功取消一次。
         */
        if (!activeRuns.remove(
                runId,
                activeRun
        )) {
            return false;
        }

        return activeRun.task().cancel(true);
    }

    /**
     * 聊天任务正常完成、失败或取消后清理。
     */
    public void remove(String runId) {
        if (StringUtils.hasText(runId)) {
            activeRuns.remove(runId);
        }
    }

    private record ActiveRun(
            String userId,
            String conversationId,
            Future<?> task) {
    }
}