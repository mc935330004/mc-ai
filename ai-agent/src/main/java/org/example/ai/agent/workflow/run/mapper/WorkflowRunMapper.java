package org.example.ai.agent.workflow.run.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;

import java.time.LocalDateTime;
import java.util.List;

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
     * 查询已经超过截止时间的运行中记录。
     *
     * 这里先查询候选记录，
     * 后续仍然通过带status条件的UPDATE争抢处理权。
     */
    List<WorkflowRun> selectStaleRunningRuns(@Param("cutoff")LocalDateTime cutoff);

    /**
     * 将指定运行记录从RUNNING更新为FAILED。
     *
     * 多实例同时恢复时，只有一个实例能够更新成功。
     */
    int failRunningRun(
            @Param("runId")
            String runId,
            @Param("finishedAt")
            LocalDateTime finishedAt,
            @Param("errorCode")
            String errorCode,
            @Param("errorMessage")
            String errorMessage
    );
}