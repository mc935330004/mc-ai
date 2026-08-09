package org.example.ai.agent.modelconfig.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.modelconfig.entity.ModelConfig;

/**
 * 模型配置Mapper。
 *
 * 当前只有简单CRUD，不需要Mapper XML。
 */
@Mapper
public interface ModelConfigMapper extends BaseMapper<ModelConfig> {
}