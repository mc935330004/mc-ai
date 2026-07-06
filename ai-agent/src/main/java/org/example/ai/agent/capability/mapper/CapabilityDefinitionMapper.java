package org.example.ai.agent.capability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.capability.entity.CapabilityDefinition;

/**
 * AI 能力定义 Mapper。
 *
 * 简单单表 CRUD 直接继承 BaseMapper 即可，不需要 XML。
 */
@Mapper
public interface CapabilityDefinitionMapper extends BaseMapper<CapabilityDefinition> {
}