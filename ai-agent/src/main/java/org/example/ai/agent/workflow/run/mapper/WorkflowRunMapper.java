package org.example.ai.agent.workflow.run.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;

import java.time.LocalDateTime;

@Mapper
public interface WorkflowRunMapper
        extends BaseMapper<WorkflowRun> {

    WorkflowRun selectByRunIdForUpdate(@Param("runId") String runId );

    Page<WorkflowRun> pageRuns(Page<WorkflowRun> page,
            @Param("userId") String userId,
            @Param("workflowCode") String workflowCode,
            @Param("status") String status,
            @Param("origin") String origin);

    /**
     * 将超过截止时间仍然处于 RUNNING 的记录标记为失败。
     *
     * 多实例同时执行时也安全，因为 SQL 只更新 RUNNING 状态。
     */
    int failStaleRunningRuns(
            @Param("cutoff")LocalDateTime cutoff,
            @Param("finishedAt")LocalDateTime finishedAt,
            @Param("errorCode")
            String errorCode,
            @Param("errorMessage")
            String errorMessage
    );
}