package org.example.ai.agent.workflow.run.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.workflow.run.entity.WorkflowRunItem;

@Mapper
public interface WorkflowRunItemMapper extends BaseMapper<WorkflowRunItem> {
}