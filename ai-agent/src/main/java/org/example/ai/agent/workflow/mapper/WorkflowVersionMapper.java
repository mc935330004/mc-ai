package org.example.ai.agent.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.workflow.entity.WorkflowVersion;

/**
 * 工作流版本Mapper。
 */
@Mapper
public interface WorkflowVersionMapper
        extends BaseMapper<WorkflowVersion> {

    WorkflowVersion selectLatestByWorkflowId( @Param("workflowId") Long workflowId);

    int retireActiveVersions( @Param("workflowId") Long workflowId);
}