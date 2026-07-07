package org.example.ai.agent.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.trace.entity.RunStep;

/**
 * Agent 运行步骤记录 Mapper。
 *
 * 简单单表 CRUD 直接用 BaseMapper，不需要 XML。
 */
@Mapper
public interface RunStepMapper extends BaseMapper<RunStep> {
}