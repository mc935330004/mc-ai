package org.example.ai.agent.capability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.capability.entity.CapabilityDefinition;

/**
 * AI 能力定义 Mapper。
 *
 * 简单单表 CRUD 直接继承 BaseMapper 即可，不需要 XML。
 */
@Mapper
public interface CapabilityDefinitionMapper extends BaseMapper<CapabilityDefinition> {

    /**
     * 分页查询能力列表。
     */
    Page<CapabilityDefinition> pageCapabilities(Page<CapabilityDefinition> page,
                                                @Param("keyword") String keyword,
                                                @Param("domain") String domain,
                                                @Param("enabled") Integer enabled);
}