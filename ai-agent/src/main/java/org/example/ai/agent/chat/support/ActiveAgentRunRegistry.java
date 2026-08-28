package org.example.ai.agent.chat.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理当前进程内的活动聊天任务，不保存业务数据。
 */
@Component
public class ActiveAgentRunRegistry {

    private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();
    /**
     * 注册会话，任务尚未开始执行时也能接收取消请求。
     */
    public void register(String runId, String userId, String conversationId, AgentStreamSession stream) {
        if (!StringUtils.hasText(runId)
                || !StringUtils.hasText(userId)
                || !StringUtils.hasText(conversationId)
                || stream == null) {
            throw new IllegalArgumentException("活动任务注册参数不能为空");
        }

        ActiveRun previous = activeRuns.putIfAbsent(
                runId,
                new ActiveRun(userId, conversationId, stream)
        );

        if (previous != null) {
            throw new IllegalStateException(
                    "Agent运行任务已存在，runId=" + runId
            );
        }
    }

    /**
     * 校验任务归属后申请取消。
     * 返回 true 表示已接受取消，不表示取消快照已经保存完成。
     */
    public boolean cancel(
            String runId,
            String userId,
            String conversationId) {

        if (!StringUtils.hasText(runId)) {
            return false;
        }

        ActiveRun activeRun = activeRuns.get(runId);

        if (activeRun == null
                || !Objects.equals(activeRun.userId(), userId)
                || !Objects.equals(activeRun.conversationId(), conversationId)) {
            return false;
        }

        return activeRun.stream().requestCancellation();
    }

    /**
     * 判断任务是否仍在当前进程运行，同时校验用户和会话归属。
     */
    public boolean isActive(String runId, String userId, String conversationId) {
        if (!StringUtils.hasText(runId)) {
            return false;
        }
        ActiveRun activeRun = activeRuns.get(runId);
        return activeRun != null
                && Objects.equals(activeRun.userId(), userId)
                && Objects.equals(activeRun.conversationId(), conversationId);
    }

    /**
     * 仅在任务执行结束或提交失败时移除。
     */
    public void remove(String runId) {
        if (StringUtils.hasText(runId)) {
            activeRuns.remove(runId);
        }
    }

    private record ActiveRun(
            String userId,
            String conversationId,
            AgentStreamSession stream) {
    }
}