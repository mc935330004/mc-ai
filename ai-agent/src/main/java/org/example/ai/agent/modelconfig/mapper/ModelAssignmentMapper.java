package org.example.ai.agent.modelconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.modelconfig.entity.ModelAssignment;

/**
 * 模型授权Mapper。
 *
 * 当前只有简单CRUD，不需要Mapper XML。
 */
@Mapper
public interface ModelAssignmentMapper extends BaseMapper<ModelAssignment> {
}