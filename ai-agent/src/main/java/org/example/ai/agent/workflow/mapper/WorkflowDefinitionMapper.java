package org.example.ai.agent.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;

/**
 * 工作流定义Mapper。
 */
@Mapper
public interface WorkflowDefinitionMapper extends BaseMapper<WorkflowDefinition> {

    Page<WorkflowDefinition> pageWorkflows(Page<WorkflowDefinition> page, @Param("keyword") String keyword,
            @Param("publishStatus") String publishStatus,
            @Param("enabled") Integer enabled);

    /**
     * 发布和修改草稿时锁定工作流定义行。
     */
    WorkflowDefinition selectByIdForUpdate( @Param("id") Long id);
}