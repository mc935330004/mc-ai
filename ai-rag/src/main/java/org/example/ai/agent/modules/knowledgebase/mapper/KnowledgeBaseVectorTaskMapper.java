package org.example.ai.agent.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;

/**
 * 知识库向量化任务 Mapper。
 */
@Mapper
public interface KnowledgeBaseVectorTaskMapper extends BaseMapper<KnowledgeBaseVectorTask> {

    /**
     * 使用本次领取令牌锁定待处理任务。
     */
    int lockPendingTask(@Param("taskId") Long taskId, @Param("claimToken") String claimToken);

    /**
     * 只有当前领取批次可以完成任务。
     */
    int completeClaim(@Param("taskId") Long taskId, @Param("claimToken") String claimToken);

    /**
     * 只有当前领取批次可以记录失败并安排重试。
     */
    int failOrRetryClaim(@Param("taskId") Long taskId,
                         @Param("claimToken") String claimToken,
                         @Param("errorMessage") String errorMessage);

    /**
     * 重置未达到最大重试次数的超时任务。
     */
    int resetTimeoutTasks(@Param("timeoutMinutes") int timeoutMinutes);

    /**
     * 将达到最大重试次数的超时任务标记为失败。
     */
    int failTimeoutTasks(@Param("timeoutMinutes") int timeoutMinutes);
}